package com.company.qagent.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器：把长文本切成适合向量化的小片段。
 * <p>策略：按段落优先切分，超长段落再按字符硬切，相邻块之间保留 overlap 重叠字符。</p>
 */
@Component
public class TextChunker {

    /**
     * 将文本切分为若干 chunk。
     *
     * @param text      原始文本
     * @param maxLength 单个 chunk 最大字符数
     * @param overlap   相邻 chunk 重叠字符数
     * @return chunk 列表
     */
    public List<String> chunk(String text, int maxLength, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        if (overlap >= maxLength) {
            overlap = Math.max(0, maxLength / 4);
        }

        // 按换行拆成段落
        String[] paragraphs = text.split("\n");
        StringBuilder current = new StringBuilder();

        for (String raw : paragraphs) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) {
                continue;
            }

            // 当前块已满，且放不下这个段落 → 存入chunks，下一段以overlap开头
            if (current.length() > 0
                    && current.length() + paragraph.length() + 1 > maxLength) {
                chunks.add(current.toString());
                current = new StringBuilder(tail(current.toString(), overlap));
            }

            if (paragraph.length() > maxLength) {
                // 超长段落：先收掉已有内容，再硬切该段落
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder(tail(current.toString(), overlap));
                }
                splitLongParagraph(paragraph, maxLength, overlap, chunks);
            } else {
                // 普通段落：追加到当前块
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(paragraph);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * 超长段落按字符硬切，每个子块末尾也带上 overlap。
     */
    private void splitLongParagraph(String paragraph, int maxLength, int overlap,
                                    List<String> chunks) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + maxLength, paragraph.length());
            chunks.add(paragraph.substring(start, end));
            if (end == paragraph.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
    }

    /**
     * 取文本末尾 overlap 个字符作为下一个块的"开头"。
     */
    private String tail(String text, int overlap) {
        if (overlap <= 0 || text.length() <= overlap) {
            return "";
        }
        return text.substring(text.length() - overlap);
    }
}
