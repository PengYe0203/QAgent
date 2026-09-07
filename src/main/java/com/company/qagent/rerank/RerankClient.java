package com.company.qagent.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Rerank 重排客户端：调用 SiliconFlow 的 BGE-reranker 模型，对召回片段精排。
 *
 * <p>原理：把【问题 + 每个候选片段】逐对送入交叉编码器打分，
 * 返回每个候选与问题的相关度分数（0~1），据此重新排序。</p>
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final boolean enabled;
    private final RestClient restClient = RestClient.builder().build();

    public RerankClient(
            @Value("${app.rerank.api-key:}") String apiKey,
            @Value("${app.rerank.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${app.rerank.model:BAAI/bge-reranker-v2-m3}") String model,
            @Value("${app.rerank.enabled:false}") boolean enabled) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * 对召回片段重排。
     *
     * @param query  用户问题
     * @param chunks 召回片段（保留原始顺序）
     * @return 与 chunks 顺序对应的 relevance_score 列表；未启用或失败时返回 null（表示跳过重排）
     */
    public List<Double> rerank(String query, List<ScoredChunkRef> chunks) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> documents = chunks.stream().map(c -> c.content()).toList();

        RerankRequest request = new RerankRequest(model, query, documents, documents.size());
        try {
            RerankResponse response = restClient.post()
                    .uri(baseUrl + "/v1/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);
            if (response == null || response.results() == null) {
                return null;
            }
            // 把按分数排序的结果，映射回原始顺序
            double[] scores = new double[documents.size()];
            for (RerankResponse.Result r : response.results()) {
                if (r.index() >= 0 && r.index() < scores.length) {
                    scores[r.index()] = r.relevanceScore();
                }
            }
            List<Double> result = new java.util.ArrayList<>();
            for (double s : scores) {
                result.add(s);
            }
            log.info("Rerank 完成: {} 个片段", result.size());
            return result;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，跳过重排: {}", e.getMessage());
            return null;
        }
    }

    /** 待排序片段的最小引用（避免 RerankClient 依赖 service 层） */
    public record ScoredChunkRef(String id, String content) {
    }

    record RerankRequest(String model, String query, List<String> documents, int top_n) {
    }

    record RerankResponse(String id, List<Result> results) {
        record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {
        }
    }
}
