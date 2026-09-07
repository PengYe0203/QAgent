package com.company.qagent.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮会话存储（内存实现）。
 *
 * <p>每个 conversationId 对应一个消息列表。生产环境可替换为 Redis / 数据库实现。</p>
 */
@Component
public class ConversationStore {

    /** 每个会话最多保留的消息数（超出丢弃最旧的，防止上下文无限膨胀） */
    private static final int MAX_MESSAGES = 10;

    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();

    /** 创建新会话并返回会话 ID */
    public String newConversation() {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new ArrayList<>());
        return id;
    }

    /** 获取会话历史（不存在则创建空会话） */
    public List<Message> getHistory(String conversationId) {
        return sessions.computeIfAbsent(conversationId, k -> new ArrayList<>());
    }

    /** 追加用户消息 */
    public void appendUser(String conversationId, String text) {
        List<Message> history = getHistory(conversationId);
        history.add(new UserMessage(text));
        trim(history);
    }

    /** 追加 AI 回复 */
    public void appendAssistant(String conversationId, String text) {
        List<Message> history = getHistory(conversationId);
        history.add(new AssistantMessage(text));
        trim(history);
    }

    /** 超出上限时丢弃最旧消息 */
    private void trim(List<Message> history) {
        while (history.size() > MAX_MESSAGES) {
            history.remove(0);
        }
    }
}
