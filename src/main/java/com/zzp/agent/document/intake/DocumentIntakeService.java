package com.zzp.agent.document.intake;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

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

    public DocumentIntakeService(DocumentParseService parseService,
                                 ChunkingService chunkingService,
                                 EmbeddingService embeddingService,
                                 ElasticsearchAsyncClient esClient,
                                 MilvusClientV2 milvusClient,
                                 @Value("${elaasticsearch.index}") String esIndex,
                                 @Value("${milvus.collection}") String milvusCollection) {
        this.parseService = parseService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.esClient = esClient;
        this.milvusClient = milvusClient;
        this.esIndex = esIndex;
        this.milvusCollection = milvusCollection;
    }

    /**
     * 摄入一份文档。返回 {@link IntakeResult}。
     */
    public IntakeResult intake(String fileName, InputStream in) throws Exception {
        // 1) 解析 + 切块
        ParseResult parsed = parseService.parse(fileName, in);
        List<TextChunk> chunks = chunkingService.chunk(parsed.documentId(), parsed.text());

        // 2) 建索引/集合（Milvus 维度取首次 embedding 的实际维度）
        int dim = ensureIndices(chunks);

        // 3) 每块 embedding + 写 ES + 写 Milvus
        for (TextChunk chunk : chunks) {
            float[] vec = embeddingService.embed(chunk.text());
            indexToEs(chunk);
            insertToMilvus(chunk, vec, dim);
        }
        return new IntakeResult(parsed.documentId(), chunks.size());
    }

    /** 确保 ES index 与 Milvus collection 存在；返回 Milvus 向量维度。 */
    private int ensureIndices(List<TextChunk> chunks) throws Exception {
        // ES index
        boolean esExists = esClient.indices().exists(b -> b.index(esIndex)).get().value();
        if (!esExists) {
            createEsIndex();  // 不存在：带 IK 分词器创建
        }
        // Milvus collection
        boolean mExists = milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(milvusCollection).build());
        if (mExists) {
            // 取现有维度（简化：不深查，用已记录的 dim）
            throw new IllegalStateException(
                    "Milvus collection 已存在，请自行确认维度一致: " + milvusCollection);
        }
        // 未创建：先用第一块 embedding 定维度
        float[] first = embeddingService.embed(chunks.get(0).text());
        createMilvusCollection(first.length);
        createMilvusIndex();
        return first.length;
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
                                        .maxLength(65535).build(),
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

    /** 写 ES：id=chunkId, body 用 DocSource(document_id,text)，经 JsonpSerializer 落库。 */
    private void indexToEs(TextChunk chunk) {
        JsonpSerializer<DocSource> ser = (value, gen, mapper) -> mapper.serialize(value, gen);
        IndexRequest<DocSource> request = IndexRequest.of(b -> b
                .index(esIndex)
                .id(chunk.chunkId())
                .document(new DocSource(chunk.documentId(), chunk.text()))
                .tDocumentSerializer(ser));
        esClient.index(request).join(); // 摄入为同步流程，阻塞等待写回完成
    }

    /** 写 Milvus：一行 JsonObject（chunkId/documentId/text/embedding）。 */
    private void insertToMilvus(TextChunk chunk, float[] vec, int dim) {
        if (vec.length != dim) {
            throw new IllegalStateException("embedding 维度 " + vec.length
                    + " 与集合维度 " + dim + " 不一致");
        }
        JsonObject row = new JsonObject();
        row.addProperty("chunkId", chunk.chunkId());
        row.addProperty("documentId", chunk.documentId());
        row.addProperty("text", chunk.text());
        row.add("embedding", GSON.toJsonTree(toDoubleArray(vec)));

        milvusClient.insert(InsertReq.builder()
                .collectionName(milvusCollection)
                .data(List.of(row))
                .build());
    }

    private double[] toDoubleArray(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            d[i] = f[i];
        }
        return d;
    }

    /** 摄入结果。 */
    public record IntakeResult(String documentId, int chunkCount) {
    }
}
