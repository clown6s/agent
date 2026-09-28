package com.zzp.agent.document.search;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzp.agent.common.document.DocHitResponse;
import com.zzp.agent.common.document.DocSource;
import com.zzp.agent.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

/**
 * 文档检索（混合召回）服务。
 *
 * <p>对一句话 query,同时从两条召回通道取候选：
 * <ul>
 *   <li><b>ES 全文</b>：text 字段上的 match/BM25,命中词面相似——擅长专有名词、术语;</li>
 *   <li><b>Milvus 向量</b>：query 先 embedding 再按余弦相似度搜,命中语义相似——擅长同义改写、语义近义。</li>
 * </ul>
 * 两侧各取一个候选池后,用 <b>RRF（Reciprocal Rank Fusion）</b> 按名次融合去重,
 * 取全局 topK 返回。候选池取 {@code topK × POOL_MULTIPLIER},给融合足量排序素材。
 *
 * <p>{@code filter} 非空时视为 <i>documentId</i>,两侧都只在该文档内召回。
 *
 * <p>健壮性：每一路独立 try/catch,任一侧失败仅记日志并退化为另一路,不让查询因单点失败整体失败。
 */
@Service
public class QueryDocumentService {

    private static final Logger log = LoggerFactory.getLogger(QueryDocumentService.class);

    /** RRF 融合常数 k,见 {@link #rrfFuse}。 */
    private static final int RRF_K = 60;

    /** 每路候选池 = topK 的倍数,给 RRF 足量排序素材。 */
    private static final int POOL_MULTIPLIER = 4;

    /** 候选池下限,防止 topK 过小时融合退化为单一源。 */
    private static final int MIN_POOL = 20;

    /** Milvus 检索需回带的字段（chunkId/documentId/text 与 ES _source 对齐）。 */
    private static final List<String> MV_OUTPUT_FIELDS = List.of("chunkId", "documentId", "text");

    private final ElasticsearchAsyncClient esClient;
    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final String esIndex;
    private final String milvusCollection;

    /**
     * 召回专用执行器（虚拟线程）：仅供并行两路召回（ES + Milvus）使用。
     * 改用虚拟线程而非公共 ForkJoinPool / 固定线程池：
     * 两路都是阻塞型网络 I/O（HttpClient、CompletableFuture.join、Milvus search），
     * 虚拟线程阻塞时会脱栈让出 carrier，不占平台线程；按需创建、随用随弃，
     * 无需猜池大小，也避免长尾/并发召回占满公共池拖累整个 JVM。
     * 线程名带 "recall-" 前缀便于排查线程dump。
     */
    private final ExecutorService recallExecutor;

    public QueryDocumentService(ElasticsearchAsyncClient esClient,
                                MilvusClientV2 milvusClient,
                                EmbeddingService embeddingService,
                                @Value("${elasticsearch.index}") String esIndex,
                                @Value("${milvus.collection}") String milvusCollection) {
        this.esClient = esClient;
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.esIndex = esIndex;
        this.milvusCollection = milvusCollection;
        // 每任务一条虚拟线程；用 newThreadPerTaskExecutor(factory) 传入命名工厂，
        // 否则默认虚拟线程名是 "Thread-0/1/..."（newVirtualThreadPerTaskExecutor 只有无参版）。
        this.recallExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recall-", 0).factory());
    }

    /** 应用关闭时回收专用执行器。 */
    @PreDestroy
    public void shutdown() {
        recallExecutor.close();
    }

    /**
     * 混合召回主入口：返回与 query 最相关的 topK 个文档块。
     *
     * @param query  用户查询
     * @param topK   返回命中数（&le;0 返回空）
     * @param filter 可选的 documentId；非空时仅召回该文档下的块
     */
    public List<DocHitResponse> getDocHitResponses(String query, int topK, String filter) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        int pool = Math.max(MIN_POOL, topK * POOL_MULTIPLIER);

        // 两路召回彼此独立、都是网络往返（Milvus 侧还含一次 query embedding HTTP 调用），
        // 串行会累计两路各自延迟；用专用虚拟线程执行器并行各放一条，总耗时收敛到慢的那一路。
        CompletableFuture<List<DocHitResponse>> esFuture =
                CompletableFuture.supplyAsync(() -> searchEs(query, pool, filter), recallExecutor);
        CompletableFuture<List<DocHitResponse>> mvFuture =
                CompletableFuture.supplyAsync(() -> searchMilvus(query, pool, filter), recallExecutor);

        List<DocHitResponse> esHits = esFuture.join();     // 失败时为空
        List<DocHitResponse> mvHits = mvFuture.join();     // 失败时为空

        return rrfFuse(esHits, mvHits, topK);
    }

    /** ES 全文召回：text 字段 match 检索，可选按 document_id 过滤。 */
    private List<DocHitResponse> searchEs(String query, int size, String filter) {
        try {
            SearchResponse<DocSource> resp = esClient.search(s -> {
                s.index(esIndex).size(size);
                if (filter == null || filter.isBlank()) {
                    s.query(q -> q.match(m -> m.field("text").query(query)));
                } else {
                    s.query(q -> q.bool(b -> b
                            .must(m -> m.match(mm -> mm.field("text").query(query)))
                            .filter(f -> f.term(t -> t.field("document_id").value(filter)))));
                }
                return s;
            }, DocSource.class).join();

            List<Hit<DocSource>> hits = resp.hits().hits();
            return hits == null ? List.of() : hits.stream()
                    .filter(h -> h.source() != null)
                    .map(DocHitResponse::from)
                    .toList();
        } catch (Exception e) {
            log.warn("ES 全文召回失败，回退到仅向量源 query={}", query, e);
            return List.of();
        }
    }

    /** Milvus 向量召回：query 向量化后按余弦相似度检索，可选按 documentId 过滤。 */
    private List<DocHitResponse> searchMilvus(String query, int size, String filter) {
        try {
            float[] queryVec = embeddingService.embed(query);

            SearchReq.SearchReqBuilder rb = SearchReq.builder()
                    .collectionName(milvusCollection)
                    .data(List.of(new FloatVec(queryVec))).limit(size)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(MV_OUTPUT_FIELDS);
            if (filter != null && !filter.isBlank()) {
                rb.filter("documentId == \"" + filter + "\"");
            }

            SearchResp resp = milvusClient.search(rb.build());
            List<SearchResp.SearchResult> results = resp.getSearchResults().getFirst();

            List<DocHitResponse> out = new ArrayList<>(results.size());
            for (SearchResp.SearchResult r : results) {
                Map<String, Object> entity = r.getEntity();
                if (entity == null) {
                    continue;
                }
                Object chunkId = entity.get("chunkId");
                Object documentId = entity.get("documentId");
                Object text = entity.get("text");
                if (chunkId == null || text == null) {
                    continue;
                }
                Float score = r.getScore();
                out.add(new DocHitResponse(
                        String.valueOf(chunkId),
                        documentId == null ? null : String.valueOf(documentId),
                        String.valueOf(text),
                        score == null ? null : score.doubleValue()));
            }
            return out;
        } catch (Exception e) {
            log.warn("Milvus 向量召回失败，回退到仅全文源 query={}", query, e);
            return List.of();
        }
    }

    /**
     * RRF 融合：对两路各自的名次打分 {@code Σ 1/(k + rank)}（rank 从 1 起），
     * 同名次分相加，按总分降序取 topK。得分用融合分，完全跨源可比。
     */
    private List<DocHitResponse> rrfFuse(List<DocHitResponse> esHits,
                                         List<DocHitResponse> mvHits,
                                         int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, DocHitResponse> detail = new LinkedHashMap<>();
        addRanked(scores, detail, esHits);
        addRanked(scores, detail, mvHits);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    DocHitResponse base = detail.get(e.getKey());
                    return new DocHitResponse(base.chunkId(), base.documentId(), base.text(), e.getValue());
                })
                .toList();
    }

    /** 把一路命中的名次以 RRF 分累加进 scores，同时记录该 chunkId 的内容细节。 */
    private void addRanked(Map<String, Double> scores,
                           Map<String, DocHitResponse> detail,
                           List<DocHitResponse> hits) {
        for (int i = 0; i < hits.size(); i++) {
            DocHitResponse h = hits.get(i);
            detail.putIfAbsent(h.chunkId(), h);
            scores.merge(h.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }
}