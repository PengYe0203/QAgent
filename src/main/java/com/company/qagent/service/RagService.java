package com.company.qagent.service;

import com.company.qagent.model.SourceRef;
import com.company.qagent.rerank.RerankClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RAG 问答服务：检索 → Rerank 精排 → 拼上下文 → DeepSeek 生成回答。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 系统提示词：约束 AI 只能基于文档回答，不编造 */
    private static final String SYSTEM_PROMPT = """
            你是一个严谨的企业文档智能助手。请严格基于下面提供的【文档片段】回答用户的问题。

            回答要求：
            1. 如果文档片段中包含答案，请用中文简洁、准确地回答；如果片段中没有答案，请明确回答"根据现有文档无法找到相关信息"，不要编造内容。
            2. 只使用提供的文档上下文，不要使用外部知识。
            3. 如果用户追问（如"那第二步呢"），请结合对话历史理解指代，再基于文档回答。
            4. 排版要求：请使用简洁的 Markdown 排版（要点可用 **加粗** 强调，步骤可用短列表或有序列表，必要时可用小表格）；标题最多用到三级，不要以一级大标题开头。
            5. 不要在回答末尾输出"参考文档 / 来源 / 出处"之类的列表，引用出处会由页面单独展示。
            """;

    private final HybridSearchService searchService;
    private final RerankClient rerankClient;
    private final ChatClient chatClient;

    public RagService(HybridSearchService searchService, RerankClient rerankClient,
                      ChatClient.Builder chatClientBuilder) {
        this.searchService = searchService;
        this.rerankClient = rerankClient;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 问答主流程：
     * ① 检索（召回更多候选）
     * ② Rerank 精排（取最相关的 topK）
     * ③ 拼上下文 → 把【历史 + 问题 + 精排片段】发给 DeepSeek
     *
     * @param history 历史对话消息（多轮对话上下文；可为空）
     */
    public ChatResult answer(String question, int topK, List<Message> history) {
        // ① 检索 + ② Rerank 精排
        List<ScoredChunk> hits = retrieve(question, topK);

        // ③ 拼上下文
        String context = buildContext(hits);

        // ④ 生成回答：system + 历史消息 + 当前问题（含上下文片段）
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)                       // 多轮对话历史
                .user(context + "\n\n【用户问题】\n" + question)
                .call()
                .content();

        // 提取引用来源（来自精排后的片段，含原文片段便于溯源）
        List<SourceRef> sources = toSources(hits);

        return new ChatResult(answer, sources);
    }

    /**
     * 流式问答：与 answer() 相同的检索+Rerank 流程，但返回引用来源与 token 流。
     * 来源在生成前就已确定，因此封装为 {@link StreamResult} 一并返回，
     * 控制器可先把来源通过 SSE meta 事件发给前端，再订阅 token 流。
     */
    public StreamResult streamChat(String question, int topK, List<Message> history) {
        // ① 检索 + ② Rerank 精排
        List<ScoredChunk> hits = retrieve(question, topK);

        // 引用来源（生成前确定）
        List<SourceRef> sources = toSources(hits);

        // ③ 拼上下文
        String context = buildContext(hits);

        // ④ 流式生成：stream().content() 返回 Flux<String>（增量 token）
        Flux<String> tokens = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(context + "\n\n【用户问题】\n" + question)
                .stream()
                .content();

        return new StreamResult(sources, tokens);
    }

    /**
     * 把精排命中的片段转成引用来源列表（文件名 + 原文位置 + 原文片段）。
     */
    private List<SourceRef> toSources(List<ScoredChunk> hits) {
        return hits.stream()
                .map(h -> new SourceRef(h.getSource(), h.getSourceRef(), h.getContent()))
                .toList();
    }

    /**
     * 检索 + Rerank 精排（answer 和 streamAnswer 共用）。
     */
    private List<ScoredChunk> retrieve(String question, int topK) {
        // ① 检索：召回 topK*3 个候选（宁可多召回，交给 Rerank 精排）
        List<ScoredChunk> candidates = searchService.search(question, topK * 3);
        log.info("检索召回 {} 个候选片段", candidates.size());

        // ② Rerank 精排
        List<ScoredChunk> hits = rerankAndSelect(question, candidates, topK);
        log.info("Rerank 精排后保留 {} 个片段", hits.size());
        return hits;
    }

    /**
     * Rerank 精排并取 topK。
     * 未启用 Rerank 时，退化为按 RRF 分数排序直接取 topK。
     */
    private List<ScoredChunk> rerankAndSelect(String question, List<ScoredChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 转成 RerankClient 需要的引用（id + content）
        List<RerankClient.ScoredChunkRef> refs = candidates.stream()
                .map(c -> new RerankClient.ScoredChunkRef(c.getId(), c.getContent()))
                .toList();

        List<Double> scores = rerankClient.rerank(question, refs);
        if (scores != null) {
            // Rerank 成功：把分数映射回候选，按分数降序取 topK
            List<ScoredChunk> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size() && i < scores.size(); i++) {
                candidates.get(i).setRerankScore(scores.get(i));
                scored.add(candidates.get(i));
            }
            scored.sort(Comparator.comparingDouble(ScoredChunk::getRerankScore).reversed());
            return scored.subList(0, Math.min(topK, scored.size()));
        } else {
            // Rerank 未启用/失败：按 RRF 分数取 topK
            List<ScoredChunk> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(ScoredChunk::getRrfScore).reversed());
            return sorted.subList(0, Math.min(topK, sorted.size()));
        }
    }

    /**
     * 把命中的片段组装成带编号的上下文文本。
     */
    private String buildContext(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder("【文档片段】\n");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append('[').append(i + 1).append("] (来源: ")
                    .append(c.getSource()).append(' ').append(c.getSourceRef()).append(")\n")
                    .append(c.getContent()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 问答结果：回答文本 + 引用来源。
     */
    public record ChatResult(String answer, List<SourceRef> sources) {
    }

    /**
     * 流式问答结果：引用来源（meta 事件用）+ 回答 token 流。
     */
    public record StreamResult(List<SourceRef> sources, Flux<String> tokens) {
    }
}
