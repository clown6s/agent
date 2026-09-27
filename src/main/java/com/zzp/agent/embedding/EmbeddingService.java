package com.zzp.agent.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 服务：调用 SiliconFlow 的 OpenAI 兼容 /embeddings 接口，把文本向量化。
 *
 * <p>单条用 {@link #embed(String)}，批量用 {@link #embedAll(List)}（一次请求传数组，
 * 实测 SiliconFlow 对 input 数组按序返回多条 data）。偶发限流/连接重置做指数退避重试。
 */
@Service
public class EmbeddingService {

    /** 最大尝试次数（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 首次退避基数（毫秒），之后逐次翻倍。 */
    private static final long BASE_BACKOFF_MS = 500L;

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public EmbeddingService(@Value("${embedding.uri}") String baseUrl,
                            @Value("${embedding.apiKey}") String apiKey,
                            @Value("${embedding.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        // baseUrl 若已是 .../embeddings 则不重复拼接；否则补全 /embeddings 端点
        String endpoint = baseUrl.endsWith("/embeddings")
                ? baseUrl
                : baseUrl + "/embeddings";

        // 用 JDK 内置 HttpClient（原生 TLS），绕开 Apache httpclient5 与 SiliconFlow 的 TLS 重置问题
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);

        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .requestFactory(factory)
                .build();
    }

    /** 对单段文本做向量化，返回 float 向量。 */
    public float[] embed(String input) {
        return embedAll(List.of(input)).getFirst();
    }

    /**
     * 对多段文本做批量向量化，一次请求传数组，按序返回与入参一一对应的向量列表。
     * 返回条数必须与入参一致，否则抛错（防止顺序错位）。
     */
    public List<float[]> embedAll(List<String> inputs) {
        EmbeddingRequest request = new EmbeddingRequest(model, inputs);
        EmbeddingResponse resp = postWithRetry(request);

        if (resp == null || resp.datas() == null || resp.datas().isEmpty()) {
            throw new IllegalStateException("Embedding 响应为空或缺少 data: " + resp);
        }
        if (resp.datas().size() != inputs.size()) {
            throw new IllegalStateException("批量 embedding 返回条数不符: 期望 "
                    + inputs.size() + " 实际 " + resp.datas().size());
        }

        List<float[]> out = new ArrayList<>(inputs.size());
        for (EmbeddingData d : resp.datas()) {
            List<Float> vec = d.embedding();
            if (vec == null) {
                throw new IllegalStateException("Embedding 响应缺少 embedding 字段");
            }
            float[] f = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                f[i] = vec.get(i);
            }
            out.add(f);
        }
        return out;
    }

    /** 指数退避重试调一次 /embeddings，吞掉偶发的限流/连接重置。 */
    private EmbeddingResponse postWithRetry(EmbeddingRequest request) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(EmbeddingResponse.class);
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = BASE_BACKOFF_MS << (attempt - 1);
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("Embedding 在 " + MAX_ATTEMPTS + " 次尝试后仍失败", last);
    }

    /** 请求体：OpenAI 兼容格式。 */
    private record EmbeddingRequest(String model, @JsonProperty("input") List<String> inputs) {
    }

    /** 响应体：{ "data": [ { "embedding": [...] } ] }。 */
    private record EmbeddingResponse(@JsonProperty("data") List<EmbeddingData> datas) {
    }

    private record EmbeddingData(List<Float> embedding) {
    }
}