package com.zzp.agent.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Embedding 服务：调用 SiliconFlow 的 OpenAI 兼容 /embeddings 接口，
 * 把一段文本向量化。
 *
 * <p>配置从环境变量注入（对应 .env：EMBEDDING_BASE_URL / SILICONFLOW_API_KEY / EMBEDDING_MODEL）。
 */
@Service
public class EmbeddingService {

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public EmbeddingService(@Value("${embedding.uri}") String baseUrl,
                            @Value("${embedding.apiKey}") String apiKey,
                            @Value("${embedding.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** 对单段文本做向量化，返回 float 向量。 */
    public float[] embed(String input) {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", List.of(input));

        Map<?, ?> resp = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (resp == null || !(resp.get("data") instanceof List<?> data) || data.isEmpty()) {
            throw new IllegalStateException("Embedding 响应为空: " + resp);
        }
        Map<?, ?> first = (Map<?, ?>) data.get(0);
        Object emb = first.get("embedding");
        if (!(emb instanceof List<?> vec)) {
            throw new IllegalStateException("Embedding 响应缺少 embedding 字段");
        }
        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            out[i] = ((Number) vec.get(i)).floatValue();
        }
        return out;
    }
}
