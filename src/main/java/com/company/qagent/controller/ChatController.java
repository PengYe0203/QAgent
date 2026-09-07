package com.company.qagent.controller;

import com.company.qagent.model.ChatRequest;
import com.company.qagent.model.ChatResponse;
import com.company.qagent.service.ConversationStore;
import com.company.qagent.service.HybridSearchService;
import com.company.qagent.service.RagService;
import com.company.qagent.service.ScoredChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答接口。
 * <ul>
 *   <li>POST /api/chat         多轮问答：基于知识库 + 会话历史回答（需 DeepSeek key）</li>
 *   <li>POST /api/chat/stream  流式问答：SSE 逐 token 返回（打字机效果）</li>
 *   <li>POST /api/chat/search  纯检索：返回命中的相关片段（不调用 DeepSeek，用于验证检索）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService ragService;
    private final HybridSearchService searchService;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper;

    public ChatController(RagService ragService, HybridSearchService searchService,
                          ConversationStore conversationStore, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.searchService = searchService;
        this.conversationStore = conversationStore;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        // ① 确定会话：首次无 conversationId → 创建；已有 → 复用
        String conversationId = request.conversationId();
        boolean isNew = (conversationId == null || conversationId.isBlank());
        if (isNew) {
            conversationId = conversationStore.newConversation();
        }

        // ② 取历史 + 存本次用户问题
        List<Message> history = conversationStore.getHistory(conversationId);
        conversationStore.appendUser(conversationId, request.question());

        // ③ 问答（携带历史，支持追问指代）
        RagService.ChatResult result = ragService.answer(request.question(), 5, history);

        // ④ 存 AI 回答到历史
        conversationStore.appendAssistant(conversationId, result.answer());

        return new ChatResponse(result.answer(), result.sources(), conversationId);
    }

    /**
     * 流式问答接口（SSE）。
     *
     * <p>事件序列：</p>
     * <pre>
     *   event: meta     →  data: {"conversationId":"...", "sources":[{"source":"文件名","sourceRef":"位置","snippet":"原文片段"}]}
     *   event: token    →  data: 回答增量文本                                  （逐个 token）
     *   event: done     →  data: {"conversationId":"..."}                     （结束）
     * </pre>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // ① 确定会话（resolveConversation 返回 final 的 id，lambda 可安全捕获）
        final String conversationId = resolveConversation(request);

        // ② 历史 + 存用户问题
        List<Message> history = conversationStore.getHistory(conversationId);
        conversationStore.appendUser(conversationId, request.question());

        SseEmitter emitter = new SseEmitter(0L);   // 0L = 不超时

        // ③ 检索 + Rerank 在此同步完成，返回引用来源与 token 流
        RagService.StreamResult result = ragService.streamChat(request.question(), 5, history);
        Flux<String> tokenFlux = result.tokens();

        // 先发会话 ID + 引用来源（meta 事件），页面据此展示"参考来源"
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("conversationId", conversationId);
            meta.put("sources", result.sources());
            String metaJson = objectMapper.writeValueAsString(meta);
            emitter.send(SseEmitter.event().name("meta").data(metaJson));
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // ④ 聚合完整回答（用于流结束后存入历史）
        StringBuilder fullAnswer = new StringBuilder();
        tokenFlux.subscribe(
                token -> {
                    try {
                        fullAnswer.append(token);
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> emitter.completeWithError(error),
                () -> {
                    // ⑤ 完成：保存完整回答到历史，发 done 事件
                    conversationStore.appendAssistant(conversationId, fullAnswer.toString());
                    try {
                        emitter.send(SseEmitter.event().name("done").data("{\"conversationId\":\"" + conversationId + "\"}"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
        );
        return emitter;
    }

    /**
     * 解析会话 ID：首次无 conversationId → 创建新会话；已有 → 复用。
     * 返回的值是 final（不改动），供 lambda 安全捕获。
     */
    private String resolveConversation(ChatRequest request) {
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return conversationStore.newConversation();
        }
        return conversationId;
    }

    /**
     * 纯检索验证接口：只返回检索命中的片段，不调用 LLM。
     */
    @PostMapping("/search")
    public List<ScoredChunk> search(@RequestBody ChatRequest request) {
        return searchService.search(request.question(), 5);
    }
}
