package com.company.qagent.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器，基于 Apache PDFBox。
 * 逐页提取文本，并以"第 X 页"作为来源标注。
 */
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf");
    }

    @Override
    public List<TextSegment> parse(InputStream inputStream, String fileName) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        // PDFBox 3 不直接接受 InputStream，需要包装成 RandomAccessReadBuffer
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(inputStream))) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text != null && !text.isBlank()) {
                    segments.add(new TextSegment(text.trim(), "第" + page + "页"));
                }
            }
        }
        return segments;
    }
}
