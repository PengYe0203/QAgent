package com.company.qagent.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Word 文档解析器（.docx），基于 Apache POI。
 * 提取正文段落与表格，表格转成"单元格 | 单元格"文本以便语义检索。
 */
@Component
public class WordParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".docx");
    }

    @Override
    public List<TextSegment> parse(InputStream inputStream, String fileName) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            // 记录"当前所在章节"，用于给后续内容标注更精确的来源
            String currentSection = "";

            // getBodyElements()：按文档真实顺序返回段落与表格（混排）
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String style = paragraph.getStyle();
                    String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    // 是标题 → 更新当前章节
                    if (style != null && style.toLowerCase().startsWith("heading")) {
                        currentSection = text;
                    } else {
                        // 普通正文 → 用当前章节作为来源
                        segments.add(new TextSegment(text, sourceRefFor(currentSection)));
                    }
                } else if (element instanceof XWPFTable table) {
                    // 表格转成 "单元格 | 单元格" 形式，方便 AI 理解结构
                    StringBuilder sb = new StringBuilder("【表格】\n");
                    for (XWPFTableRow row : table.getRows()) {
                        String line = row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .map(String::trim)
                                .collect(Collectors.joining(" | "));
                        if (!line.isBlank()) {
                            sb.append(line).append('\n');
                        }
                    }
                    if (sb.length() > "【表格】\n".length()) {
                        segments.add(new TextSegment(sb.toString().trim(), sourceRefFor(currentSection)));
                    }
                }
            }
        }
        return segments;
    }

    /**
     * 生成来源标注：有章节信息用章节，否则回退到"正文"。
     */
    private String sourceRefFor(String currentSection) {
        return currentSection.isEmpty() ? "正文" : "章节:" + currentSection;
    }
}
