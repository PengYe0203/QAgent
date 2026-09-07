package com.company.qagent.model;

/**
 * 问答请求。
 *
 * @param question       用户问题
 * @param conversationId 会话 ID（多轮对话时传入以保持上下文；首次可为空，后端自动创建）
 */
public record ChatRequest(String question, String conversationId) {
}
