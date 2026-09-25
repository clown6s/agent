package com.zzp.agent.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Embedding 服务：调用 SiliconFlow 的 OpenAI 兼容 /embeddings 接口，把一段文本向量化。
 *
 * <p>响应用嵌套 DTO record 反序列化（编译期锁定字段名），替代手拆 Map。
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
        EmbeddingRequest request = new EmbeddingRequest(model, List.of(input));
        EmbeddingResponse resp = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (resp == null || resp.datas() == null || resp.datas().isEmpty()) {
            throw new IllegalStateException("Embedding 响应为空或缺少 data: " + resp);
        }
        List<Float> vec = resp.datas().get(0).embedding();
        if (vec == null) {
            throw new IllegalStateException("Embedding 响应缺少 embedding 字段");
        }
        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            out[i] = vec.get(i);
        }
        return out;
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
