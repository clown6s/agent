package com.zzp.agent.document.intake;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.json.JsonpSerializer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zzp.agent.common.document.DocSource;
import com.zzp.agent.document.parser.DocumentParseService;
import com.zzp.agent.document.parser.ParseResult;
import com.zzp.agent.document.split.ChunkingService;
import com.zzp.agent.document.split.TextChunk;
import com.zzp.agent.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档摄入服务：把一份文档走完「解析 → 切块 → embedding → 写 ES(全文) → 写 Milvus(向量)」。
 */
@Service
public class DocumentIntakeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeService.class);

    private static final Gson GSON = new Gson();

    private final DocumentParseService parseService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchAsyncClient esClient;
    private final MilvusClientV2 milvusClient;

    private final String esIndex;
    private final String milvusCollection;
    private final int milvusDim;

    /** 本 JVM 内 Milvus collection 是否已 load 过。loadCollection 是阻塞往返（sync, 超时 60s），
     * 一旦加载成功便常驻，无需每次摄入重复触发。 */
    private volatile boolean milvusLoaded = false;

    public DocumentIntakeService(DocumentParseService parseService,
                                 ChunkingService chunkingService,
                                 EmbeddingService embeddingService,
                                 ElasticsearchAsyncClient esClient,
                                 MilvusClientV2 milvusClient,
                                 @Value("${elasticsearch.index}") String esIndex,
                                 @Value("${milvus.collection}") String milvusCollection,
                                 @Value("${milvus.dim}") int milvusDim) {
        this.parseService = parseService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.esClient = esClient;
        this.milvusClient = milvusClient;
        this.esIndex = esIndex;
        this.milvusCollection = milvusCollection;
        this.milvusDim = milvusDim;
    }

    /**
     * 摄入一份文档。返回 {@link IntakeResult}。
     * 任一步失败（写入 ES/Milvus 之后）会补偿删除本 documentId 已写入的块，保证不留半份数据。
     */
    public IntakeResult intake(String fileName, InputStream in) throws Exception {
        // 1) 解析 + 切块（读完后关闭输入流，避免文件句柄泄漏）
        ParseResult parsed;
        List<TextChunk> chunks;
        try (InputStream is = in) {
            parsed = parseService.parse(fileName, is);
            chunks = chunkingService.chunk(parsed.documentId(), parsed.text());
        }
        String documentId = parsed.documentId();

        try {
            // 2) 建索引/集合（Milvus 维度取配置 milvusDim）
            ensureIndices();

            // 3) 批量 embedding：一次请求向量化全部块（SiliconFlow 支持 input 数组），逐块校验维度
            List<String> texts = new ArrayList<>(chunks.size());
            for (TextChunk chunk : chunks) {
                texts.add(chunk.text());
            }
            List<float[]> vecs = embeddingService.embedAll(texts);
            if (vecs.size() != chunks.size()) {
                throw new IllegalStateException("embedding 返回条数 " + vecs.size()
                        + " 与块数 " + chunks.size() + " 不一致");
            }
            for (float[] vec : vecs) {
                if (vec.length != milvusDim) {
                    throw new IllegalStateException("embedding 维度 " + vec.length
                            + " 与集合维度 " + milvusDim + " 不一致");
                }
            }

            // 4) 批量写 ES
            indexToEsBatch(chunks);
            // 5) 批量写 Milvus
            insertToMilvusBatch(chunks, vecs);

            return new IntakeResult(documentId, chunks.size());
        } catch (Exception e) {
            // 失败：尽力补偿删除本 documentId 已写入 ES/Milvus 的块，不留半份；
            // 补删失败只记日志，不覆盖原始异常
            log.warn("DocumentIntake ingestion failed (fileName={}), compensating cleanup ...",
                    fileName, e);
            cleanup(documentId);
            throw e;
        }
    }

    /**
     * 补偿删除：把该 documentId 在 ES 与 Milvus 中已写入的块全部删除。
     * best-effort：任一侧失败仅记录日志，保证不因补删失败覆盖/丢失原始异常。
     */
    private void cleanup(String documentId) {
        // Milvus：按 documentId 字段筛删
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(milvusCollection)
                    .filter("documentId == \"" + documentId + "\"")
                    .build());
        } catch (Exception e) {
            log.error("Milvus 补偿删除失败 documentId={}", documentId, e);
        }
        // ES：deleteByQuery 按 document_id 字段筛删
        try {
            DeleteByQueryRequest req = DeleteByQueryRequest.of(b -> b
                    .index(esIndex)
                    .query(q -> q.term(t -> t.field("document_id").value(documentId)))
                    .refresh(true));
            esClient.deleteByQuery(req).join();
        } catch (Exception e) {
            log.error("ES 补偿删除失败 documentId={}", documentId, e);
        }
    }

    /** 确保 ES index 与 Milvus collection 存在。 */
    private void ensureIndices() throws Exception {
        // ES index
        boolean esExists = esClient.indices().exists(b -> b.index(esIndex)).get().value();
        if (!esExists) {
            createEsIndex();  // 不存在：带 IK 分词器创建
        }
        // Milvus collection
        boolean mExists = milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(milvusCollection).build());
        if (!mExists) {
            // 未创建：用配置的 milvusDim 建集合 + 索引
            createMilvusCollection(milvusDim);
            createMilvusIndex();
        }
        // 已存在则不重建（维度由配置 milvusDim 与 embedding 校验保证一致）

        // Milvus：collection 创建后必须 load 才能 search/query/delete（insert 可不用）。
        // 无论新建还是已存在，都先加载，否则后续写入/补偿删除会报 Collection Not Loaded。
        // 用 milvusLoaded 缓存：仅首次阻塞加载一次，后续摄入直接复用（集合常驻内存）。
        if (!milvusLoaded) {
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(milvusCollection)
                    .build());
            milvusLoaded = true;
        }
    }

    private void createMilvusCollection(int dim) {
        CreateCollectionReq.CollectionSchema schema =
                CreateCollectionReq.CollectionSchema.builder()
                        .fieldSchemaList(List.of(
                                CreateCollectionReq.FieldSchema.builder()
                                        .name("chunkId").dataType(DataType.VarChar)
                                        .maxLength(256).isPrimaryKey(true).build(),
                                CreateCollectionReq.FieldSchema.builder()
                                        .name("documentId").dataType(DataType.VarChar)
                                        .maxLength(64).build(),
                                CreateCollectionReq.FieldSchema.builder()
                                        .name("text").dataType(DataType.VarChar)
                                        .maxLength(16384).build(),
                                CreateCollectionReq.FieldSchema.builder()
                                        .name("embedding").dataType(DataType.FloatVector)
                                        .dimension(dim).build()
                        ))
                        .build();
        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(milvusCollection)
                .collectionSchema(schema)
                .build());
    }

    private void createMilvusIndex() {
        IndexParam index = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(milvusCollection)
                .indexParams(List.of(index))
                .build());
    }

    /**
     * 创建 ES index。字段与写入的 _source 结构对齐：
     * <ul>
     *   <li>{@code document_id}：keyword，可索引——cleanup 按它做 term 精确筛删，不能 index(false)；</li>
     *   <li>{@code text}：用 ik 分词器做全文检索（需 ES 已安装 analysis-ik 插件）；</li>
     * </ul>
     * chunkId 不落映射：它就是 ES 文档 _id（doc id），无需作为存储字段。
     * 仅在 index 不存在时调用。
     */
    private void createEsIndex() throws Exception {
        TypeMapping mapping = TypeMapping.of(m -> m
                .properties("document_id", Property.of(p -> p.keyword(
                        k -> k            // keyword 默认可索引，供 term 筛删
                )))
                .properties("text", Property.of(p -> p.text(
                        t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")
                ))));
        esClient.indices().create(b -> b
                .index(esIndex)
                .mappings(mapping)).get();
    }

    /**
     * 批量写 ES：一次 _bulk 投递所有块（id=chunkId, body 用 DocSource(document_id,text)）。
     * 逐项校验写入结果，任一失败抛错（由上层触发补偿删除）。
     */
    private void indexToEsBatch(List<TextChunk> chunks) {
        JsonpSerializer<Object> ser = (value, gen, mapper) -> mapper.serialize(value, gen);
        BulkRequest req = BulkRequest.of(b -> b
                .index(esIndex)
                .operations(chunks.stream()
                        .map(chunk -> BulkOperation.of(op -> op.index(i -> i
                                .id(chunk.chunkId())
                                .document(new DocSource(chunk.documentId(), chunk.text()))
                                .tDocumentSerializer(ser))))
                        .toList()));
        BulkResponse resp = esClient.bulk(req).join();
        if (resp.errors()) {
            throw new IllegalStateException("ES _bulk 存在失败项, 首个错误: " + resp.items().getFirst().error());
        }
    }

    /** 批量写 Milvus：一次 InsertReq 传多行 JsonObject（chunkId/documentId/text/embedding），校验插入条数。 */
    private void insertToMilvusBatch(List<TextChunk> chunks, List<float[]> vecs) {
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("chunkId", chunk.chunkId());
            row.addProperty("documentId", chunk.documentId());
            row.addProperty("text", chunk.text());
            row.add("embedding", GSON.toJsonTree(vecs.get(i)));  // 直接存 float[]，贴合 FloatVector
            rows.add(row);
        }
        InsertResp resp = milvusClient.insert(InsertReq.builder()
                .collectionName(milvusCollection)
                .data(rows)
                .build());
        if (resp.getInsertCnt() != chunks.size()) {
            throw new IllegalStateException("Milvus 插入条数异常: 期望 " + chunks.size()
                    + " 实际 " + resp.getInsertCnt());
        }
    }

    /** 摄入结果。 */
    public record IntakeResult(String documentId, int chunkCount) {
    }
}
