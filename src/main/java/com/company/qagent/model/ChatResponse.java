package com.company.qagent.model;

import java.util.List;

/**
 * 问答响应。
 *
 * @param answer         回答正文
 * @param sources        引用来源列表
 * @param conversationId 会话 ID（前端保存，用于后续追问）
 */
public record ChatResponse(String answer, List<SourceRef> sources, String conversationId) {
}
