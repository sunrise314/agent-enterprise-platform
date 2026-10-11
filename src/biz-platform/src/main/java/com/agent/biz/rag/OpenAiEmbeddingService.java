package com.agent.biz.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 embedding 实现（生产）。
 *
 * 通过配置的 base-url / api-key / model 调用 /embeddings 接口。
 * 私有化部署可指向本地 BGE 服务（如 Xinference / vLLM / Ollama /embedding）。
 */
@Profile({"prod-milvus", "milvus"})
@Component
public class OpenAiEmbeddingService implements EmbeddingService {

    private final RestTemplate rest;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public OpenAiEmbeddingService(
            @Value("${rag.embedding.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${rag.embedding.api-key:}") String apiKey,
            @Value("${rag.embedding.model:text-embedding-3-small}") String model,
            @Value("${rag.embedding.dimension:1536}") int dimension) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.rest = new RestTemplate();
    }

    @Override
    public float[] embed(String text) {
        Map<?, ?> body = Map.of("model", model, "input", List.of(text));
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        var req = new org.springframework.http.HttpEntity<>(body, headers);
        var resp = rest.postForObject(baseUrl + "/embeddings", req, Map.class);
        List<?> data = (List<?>) resp.get("data");
        Map<?, ?> first = (Map<?, ?>) data.get(0);
        List<Number> embedding = (List<Number>) first.get("embedding");
        float[] vec = new float[embedding.size()];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = embedding.get(i).floatValue();
        }
        return vec;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
