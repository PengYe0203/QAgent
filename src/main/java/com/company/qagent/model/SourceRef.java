package com.company.qagent.model;

/**
 * 引用来源。
 *
 * @param source    文档文件名
 * @param sourceRef 文档内位置（页码/章节/Sheet 名）
 * @param snippet   命中的原文片段（供前端溯源核对；可能为空）
 */
public record SourceRef(String source, String sourceRef, String snippet) {
}
