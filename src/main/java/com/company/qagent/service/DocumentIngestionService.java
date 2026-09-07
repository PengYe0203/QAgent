package com.company.qagent.service;

import com.company.qagent.chunker.TextChunker;
import com.company.qagent.model.IngestResult;
import com.company.qagent.parser.DocumentParser.TextSegment;
import com.company.qagent.parser.DocumentParserFactory;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档入库服务：解析 → 分块 → 向量化 → 写入向量库。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** 分块大小：单个 chunk 最大字符数 */
    private static final int CHUNK_SIZE = 500;
    /** 分块重叠：相邻 chunk 重叠字符数 */
    private static final int CHUNK_OVERLAP = 60;

    private final DocumentParserFactory parserFactory;
    private final TextChunker textChunker;
    private final VectorStore vectorStore;

    public DocumentIngestionService(DocumentParserFactory parserFactory,
                                    TextChunker textChunker,
                                    VectorStore vectorStore) {
        this.parserFactory = parserFactory;
        this.textChunker = textChunker;
        this.vectorStore = vectorStore;
    }

    /**
     * 解析并入库单个文档。
     */
    public IngestResult ingest(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // ① 解析：二进制文档 → 带来源标注的文本片段
        List<TextSegment> segments =
                parserFactory.getParser(fileName).parse(file.getInputStream(), fileName);
        if (segments.isEmpty()) {
            return new IngestResult(fileName, null, 0, 0, "SKIPPED_EMPTY");
        }

        // ② 分块：每个片段 → 若干 chunk，并携带元数据
        String fileId = UUID.randomUUID().toString();
        List<Document> documents = new ArrayList<>();
        int chunkIndex = 0;
        for (TextSegment segment : segments) {
            for (String chunkText : textChunker.chunk(segment.text(), CHUNK_SIZE, CHUNK_OVERLAP)) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", fileName);        // 来源文件名
                metadata.put("file_id", fileId);         // 文件 ID
                metadata.put("source_ref", segment.sourceRef()); // 来源位置（页码/章节/Sheet）
                metadata.put("chunk_index", chunkIndex++);
                // 中文分词后的文本：让 PostgreSQL tsvector 全文检索能处理中文
                // 例："员工报销制度" → "员工 报销 制度"（词间空格，simple 配置按空格切分）
                metadata.put("content_zh", tokenizeForSearch(chunkText));
                documents.add(new Document(chunkText, metadata));
            }
        }

        // ③ 向量化 + 入库（Spring AI 的 VectorStore 自动完成）
        vectorStore.add(documents);
        log.info("文档入库完成: {} ({} 个片段 -> {} 个分块)", fileName, segments.size(), documents.size());
        return new IngestResult(fileName, fileId, segments.size(), documents.size(), "SUCCESS");
    }

    /**
     * 用 HanLP 对文本做中文分词，词之间用空格连接。
     * 这样 PostgreSQL 的 simple 配置（按空格切分）就能正确建立中文全文索引。
     */
    private String tokenizeForSearch(String text) {
        return HanLP.segment(text).stream()
                .map(term -> term.word.trim())
                .filter(word -> !word.isBlank())
                .collect(Collectors.joining(" "));
    }
}
