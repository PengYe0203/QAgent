package com.company.qagent.service;

import java.util.Map;

/**
 * 检索命中的文档分块，附带各阶段分数。
 */
public class ScoredChunk {

    private final String id;
    private final String content;
    private final Map<String, Object> metadata;

    /** 向量检索得分（余弦相似度） */
    private double vectorScore;
    /** 关键词检索得分 */
    private double keywordScore;
    /** RRF 融合得分 */
    private double rrfScore;
    /** Rerank 重排得分 */
    private double rerankScore = 1.0;

    public ScoredChunk(String id, String content, Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.metadata = metadata;
    }

    public String getSource() {
        Object v = metadata.get("source");
        return v == null ? "未知来源" : v.toString();
    }

    public String getSourceRef() {
        Object v = metadata.get("source_ref");
        return v == null ? "" : v.toString();
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, Object> getMetadata() { return metadata; }
    public double getVectorScore() { return vectorScore; }
    public void setVectorScore(double vectorScore) { this.vectorScore = vectorScore; }
    public double getKeywordScore() { return keywordScore; }
    public void setKeywordScore(double keywordScore) { this.keywordScore = keywordScore; }
    public double getRrfScore() { return rrfScore; }
    public void setRrfScore(double rrfScore) { this.rrfScore = rrfScore; }
    public double getRerankScore() { return rerankScore; }
    public void setRerankScore(double rerankScore) { this.rerankScore = rerankScore; }
}
