package com.company.qagent.model;

/**
 * 文档入库结果。
 *
 * @param fileName 文档文件名
 * @param fileId   本次入库生成的文件 ID（用于将来删除）
 * @param segments 解析出的文本片段数
 * @param chunks   实际写入向量库的分块数
 * @param status   状态（SUCCESS / SKIPPED_EMPTY）
 */
public record IngestResult(String fileName, String fileId, int segments, int chunks, String status) {
}
