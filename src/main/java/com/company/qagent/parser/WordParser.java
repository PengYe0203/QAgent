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
 *
 * <p>提取正文段落与表格，表格转成"单元格 | 单元格"文本以便语义检索。</p>
 *
 * <p><b>分段策略（关键）</b>：不按自然段逐段切块，而是把"同一章节"（标题与标题之间，
 * 含标题行本身）的连续内容累积成一个文本段，交还给下游按字符分块。</p>
 *
 * <p>原因：Word 里大量短段落（序号条目、标题、图注等）如果每段独立成一个
 * TextSegment，入库时就会被各自切成孤立的小块，丢失与前后文的关联
 * （例如"6.如何创建Java线程"会单独成块，和下面的(1)(2)(3)断开）。
 * 累积后再交给 {@code TextChunker}，它会把相邻短行打包进同一个块。</p>
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
            // 当前章节标题（用于来源标注）
            String currentSection = "";
            // 正在累积的章节内容（未到达章节边界前不落盘）
            StringBuilder pending = new StringBuilder();

            // getBodyElements()：按文档真实顺序返回段落与表格（混排）
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String style = paragraph.getStyle();
                    String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (style != null && style.toLowerCase().startsWith("heading")) {
                        // 遇到新标题：先收掉上一章节的内容，再开启新章节
                        flush(pending, currentSection, segments);
                        currentSection = text;
                        // 标题行本身也并入新章节开头，避免"只有一行标题"的孤立块
                        appendLine(pending, text);
                    } else {
                        // 普通正文：累积进当前章节（含序号条目等短行）
                        appendLine(pending, text);
                    }
                } else if (element instanceof XWPFTable table) {
                    // 表格转成 "单元格 | 单元格" 文本行，同样并入当前累积，
                    // 保持与前后段落的文档顺序
                    StringBuilder tb = new StringBuilder("【表格】");
                    for (XWPFTableRow row : table.getRows()) {
                        String line = row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .map(String::trim)
                                .collect(Collectors.joining(" | "));
                        if (!line.isBlank()) {
                            tb.append('\n').append(line);
                        }
                    }
                    if (tb.length() > "【表格】".length()) {
                        appendBlock(pending, tb.toString());
                    }
                }
            }
            // 收尾：文档结尾的章节
            flush(pending, currentSection, segments);
        }
        return segments;
    }

    /** 追加一行正文（自动用换行分隔相邻行） */
    private void appendLine(StringBuilder pending, String line) {
        if (pending.length() > 0) {
            pending.append('\n');
        }
        pending.append(line);
    }

    /** 追加一段多行内容（内部自带换行，如表格） */
    private void appendBlock(StringBuilder pending, String block) {
        if (pending.length() > 0) {
            pending.append('\n');
        }
        pending.append(block);
    }

    /** 把当前累积的章节内容落为一个 TextSegment 并清空 */
    private void flush(StringBuilder pending, String currentSection, List<TextSegment> segments) {
        if (pending != null && !pending.toString().isBlank()) {
            segments.add(new TextSegment(pending.toString().trim(), sourceRefFor(currentSection)));
        }
        pending.setLength(0);
    }

    /**
     * 生成来源标注：有章节信息用章节，否则回退到"正文"。
     */
    private String sourceRefFor(String currentSection) {
        return currentSection.isEmpty() ? "正文" : "章节:" + currentSection;
    }
}
