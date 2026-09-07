package com.company.qagent.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface DocumentParser {

    // 判断是否支持文件类型，仅支持word、pdf、excel
    boolean supports(String fileName);

     // 解析文档，返回文本片段列表。每个片段携带来源标注（如页码 / Sheet 名）
     // 便于后续回答时溯源
    List<TextSegment> parse(InputStream inputStream, String fileName) throws IOException;

    // 参考文本 + 文本出处
    record TextSegment(String text, String sourceRef) {}
}
