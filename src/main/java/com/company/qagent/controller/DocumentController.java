package com.company.qagent.controller;

import com.company.qagent.model.IngestResult;
import com.company.qagent.service.DocumentIngestionService;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理接口。
 * <ul>
 *   <li>POST   /api/documents        上传单个文档并入库</li>
 *   <li>POST   /api/documents/batch  批量上传入库</li>
 *   <li>GET    /api/documents        列出已入库文档</li>
 *   <li>DELETE /api/documents?source=xxx 删除某文档</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final JdbcTemplate jdbcTemplate;

    public DocumentController(DocumentIngestionService ingestionService, JdbcTemplate jdbcTemplate) {
        this.ingestionService = ingestionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 上传单个文档并入库。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        return ingestionService.ingest(file);
    }

    /**
     * 批量上传入库。
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<IngestResult> uploadBatch(@RequestParam("files") List<MultipartFile> files) throws IOException {
        return files.stream()
                .map(f -> {
                    try {
                        return ingestionService.ingest(f);
                    } catch (IOException e) {
                        throw new RuntimeException("解析文件失败: " + f.getOriginalFilename(), e);
                    }
                })
                .toList();
    }

    /**
     * 列出已入库文档（按来源文件名分组，统计分块数）。
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        String sql = """
                SELECT metadata->>'source' AS source,
                       COUNT(*) AS chunks,
                       MIN(metadata->>'file_id') AS file_id
                FROM vector_store
                GROUP BY metadata->>'source'
                ORDER BY source
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> doc = new HashMap<>();
            doc.put("source", rs.getString("source"));
            doc.put("chunks", rs.getInt("chunks"));
            doc.put("fileId", rs.getString("file_id"));
            return doc;
        });
    }

    /**
     * 按文件名删除该文档的全部分块。
     */
    @DeleteMapping
    public void delete(@RequestParam("source") String source) {
        jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'source' = ?", source);
    }
}
