package com.company.qagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合检索服务：多路召回 + RRF 融合。
 *
 * <p>三路召回，各取所长：</p>
 * <ol>
 *   <li><b>向量检索</b>：语义相似度召回（理解"意思"）</li>
 *   <li><b>全文检索</b>：PostgreSQL tsvector 精确关键词匹配（理解"字面"）</li>
 *   <li><b>trgm 模糊匹配</b>：对中文、无空格文本的相似度匹配</li>
 * </ol>
 *
 * <p>融合用 <b>RRF（Reciprocal Rank Fusion）</b>：
 * score = Σ 1/(k + rank)，k 取 60。
 * 每个通道按自己的排序给分，排名越靠前分越高，再叠加求综合分。</p>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    /** RRF 常量：避免单个通道的排名"霸榜" */
    private static final double RRF_K = 60.0;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public HybridSearchService(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 混合检索主入口：三路召回 → RRF 融合 → 返回综合排序结果。
     */
    public List<ScoredChunk> search(String query, int topK) {
        // 三路召回（每路取多一点，融合时才有得选）
        List<List<ScoredChunk>> channels = List.of(
                vectorSearch(query, topK * 2),
                keywordSearch(query, topK * 2),
                trgmSearch(query, topK * 2)
        );
        List<ScoredChunk> results = rrfFusion(channels, topK);
        log.info("混合检索: 问题='{}' 融合后返回 {} 条", query, results.size());
        return results;
    }

    /** 通道 1：向量检索（语义相似度） */
    private List<ScoredChunk> vectorSearch(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        List<Document> hits = vectorStore.similaritySearch(request);
        return hits.stream()
                .map(d -> {
                    ScoredChunk c = new ScoredChunk(d.getId(), d.getText(), d.getMetadata());
                    c.setVectorScore(d.getScore());
                    return c;
                })
                .toList();
    }

    /** 通道 2：关键词检索（HanLP 分词 + PostgreSQL tsvector 全文检索） */
    private List<ScoredChunk> keywordSearch(String query, int topK) {
        // ① 查询也用 HanLP 分词，转成空格分隔的词序列，供 websearch_to_tsquery 使用
        //    "员工报销流程" → "员工 报销 流程"
        String tokenizedQuery = HanLP.segment(query).stream()
                .map(term -> term.word.trim())
                .filter(word -> !word.isBlank())
                .collect(Collectors.joining(" "));
        if (tokenizedQuery.isBlank()) {
            return List.of();
        }

        // ② tsvector 全文检索：匹配入库时存的分词列 content_zh（存在 metadata 里）
        //    （content_zh 是 HanLP 分词后、空格分隔的文本，simple 配置按空格正确切分）
        String sql = """
                SELECT id::text, content, metadata::text,
                       ts_rank(to_tsvector('simple', metadata->>'content_zh'),
                               websearch_to_tsquery('simple', ?)) AS rank
                FROM vector_store
                WHERE to_tsvector('simple', metadata->>'content_zh') @@ websearch_to_tsquery('simple', ?)
                ORDER BY rank DESC
                LIMIT ?
                """;
        log.info("[keywordSearch] tokenizedQuery='{}' 长度={}", tokenizedQuery, tokenizedQuery.length());
        List<ScoredChunk> result = jdbcTemplate.query(sql, (rs, rowNum) -> {
            ScoredChunk c = new ScoredChunk(
                    rs.getString("id"),
                    rs.getString("content"),
                    parseMetadata(rs.getString("metadata")));
            c.setKeywordScore(rs.getDouble("rank"));
            return c;
        }, tokenizedQuery, tokenizedQuery, topK);
        log.info("[keywordSearch] 命中 {} 条", result.size());
        return result;
    }

    /** 通道 3：pg_trgm 三元组模糊匹配（对中文友好） */
    private List<ScoredChunk> trgmSearch(String query, int topK) {
        String sql = """
                SELECT id::text, content, metadata::text,
                       similarity(content, ?) AS sim
                FROM vector_store
                WHERE content % ?
                ORDER BY sim DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ScoredChunk c = new ScoredChunk(
                    rs.getString("id"),
                    rs.getString("content"),
                    parseMetadata(rs.getString("metadata")));
            c.setKeywordScore(rs.getDouble("sim"));
            return c;
        }, query, query, topK);
    }

    /**
     * RRF 融合：score(chunk) = Σ_通道 1/(RRF_K + 该通道内排名)。
     * 同一片段多路命中时，合并各通道的分数（而非只保留第一个实例）。
     */
    private List<ScoredChunk> rrfFusion(List<List<ScoredChunk>> channels, int topK) {
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        for (List<ScoredChunk> channel : channels) {
            int rank = 1;
            for (ScoredChunk hit : channel) {
                // 累加 RRF 分数
                rrfScores.merge(hit.getId(), 1.0 / (RRF_K + rank), Double::sum);

                // 合并同 id 的实例：保留已有，但补上空缺的分数
                ScoredChunk existing = merged.get(hit.getId());
                if (existing == null) {
                    merged.put(hit.getId(), hit);
                } else {
                    // 合并各通道分数：vectorScore 取最大值，keywordScore 取最大值
                    if (hit.getVectorScore() > existing.getVectorScore()) {
                        existing.setVectorScore(hit.getVectorScore());
                    }
                    if (hit.getKeywordScore() > existing.getKeywordScore()) {
                        existing.setKeywordScore(hit.getKeywordScore());
                    }
                }
                rank++;
            }
        }

        return merged.values().stream()
                .peek(c -> c.setRrfScore(rrfScores.getOrDefault(c.getId(), 0.0)))
                .sorted(Comparator.comparingDouble(ScoredChunk::getRrfScore).reversed())
                .limit(topK)
                .toList();
    }

    /** 把数据库的 metadata json 字符串解析成 Map */
    private Map<String, Object> parseMetadata(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
