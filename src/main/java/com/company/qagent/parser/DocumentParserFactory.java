package com.company.qagent.parser;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析器工厂：根据文件扩展名分发到具体的解析器。
 */
@Component
public class DocumentParserFactory {

    private final List<DocumentParser> parsers = List.of(
            new PdfParser(),
            new WordParser(),
            new ExcelParser()
    );

    /**
     * 获取与文件名匹配的解析器。
     *
     * @throws IllegalArgumentException 不支持的格式
     */
    public DocumentParser getParser(String fileName) {
        return parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文档格式: " + fileName + "（支持 pdf / docx / xlsx / xls）"));
    }
}
