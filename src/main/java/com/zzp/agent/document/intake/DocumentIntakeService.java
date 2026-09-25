package com.zzp.agent.document.intake;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch._types.Result;
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
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 文档摄入服务：把一份文档走完「解析 → 切块 → embedding → 写 ES(全文) → 写 Milvus(向量)」。
 */
@Service
public class DocumentIntakeService {

    private static final Gson GSON = new Gson();

    private final DocumentParseService parseService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchAsyncClient esClient;
    private final MilvusClientV2 milvusClient;

    private final String esIndex;
    private final String milvusCollection;
    private final int milvusDim;

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
     */
    public IntakeResult intake(String fileName, InputStream in) throws Exception {
        // 1) 解析 + 切块
        ParseResult parsed = parseService.parse(fileName, in);
        List<TextChunk> chunks = chunkingService.chunk(parsed.documentId(), parsed.text());

        // 2) 建索引/集合（Milvus 维度取配置 milvusDim）
        ensureIndices();

        // 3) 批量 embedding：先全部向量化，维度统一校验
        List<float[]> vecs = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            float[] vec = embeddingService.embed(chunk.text());
            if (vec.length != milvusDim) {
                throw new IllegalStateException("embedding 维度 " + vec.length
                        + " 与集合维度 " + milvusDim + " 不一致");
            }
            vecs.add(vec);
        }

        // 4) 批量写 ES
        indexToEsBatch(chunks);
        // 5) 批量写 Milvus
        insertToMilvusBatch(chunks, vecs);

        return new IntakeResult(parsed.documentId(), chunks.size());
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
                                        .name("text").dataType(DataType.Text)
                                        .build(),
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
     * 创建 ES index：text 字段用 ik 分词器（需 ES 服务器已安装 analysis-ik 插件）。
     * 仅在 index 不存在时调用。
     */
    private void createEsIndex() throws Exception {
        TypeMapping mapping = TypeMapping.of(m -> m
                .properties("chunkId", Property.of(p -> p.keyword(
                        k -> k.index(false)          // 主键，不全文索引
                )))
                .properties("documentId", Property.of(p -> p.keyword(
                        k -> k.index(false)
                )))
                .properties("text", Property.of(p -> p.text(
                        t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")
                ))));
        esClient.indices().create(b -> b
                .index(esIndex)
                .mappings(mapping)).get();
    }

    /** 批量写 ES：id=chunkId, body 用 DocSource(document_id,text)，经 JsonpSerializer 落库；校验写入结果。 */
    private void indexToEsBatch(List<TextChunk> chunks) {
        JsonpSerializer<DocSource> ser = (value, gen, mapper) -> mapper.serialize(value, gen);
        List<CompletableFuture<IndexResponse>> futures = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            IndexRequest<DocSource> request = IndexRequest.of(b -> b
                    .index(esIndex)
                    .id(chunk.chunkId())
                    .document(new DocSource(chunk.documentId(), chunk.text()))
                    .tDocumentSerializer(ser));
            futures.add(esClient.index(request));
        }
        // 并行等待全部写回完成，并校验每个结果
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        for (int i = 0; i < futures.size(); i++) {
            Result r = futures.get(i).join().result();
            if (r != Result.Created && r != Result.Updated) {
                throw new IllegalStateException("ES 写入失败: chunkId=" + chunks.get(i).chunkId()
                        + " result=" + r);
            }
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
