package com.company.qagent.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 解析器（.xlsx / .xls），基于 Apache POI。
 * 逐 Sheet、逐行转成"单元格 | 单元格"文本，来源标注为 Sheet 名。
 */
@Component
public class ExcelParser implements DocumentParser {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public List<TextSegment> parse(InputStream inputStream, String fileName) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                StringBuilder sb = new StringBuilder("【Sheet: " + sheet.getSheetName() + "】\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    int lastCellNum = row.getLastCellNum();
                    if (lastCellNum <= 0) {
                        continue;   // 空行跳过
                    }
                    for (int c = 0; c < lastCellNum; c++) {
                        Cell cell = row.getCell(c);
                        String value = cell == null ? "" : FORMATTER.formatCellValue(cell).trim();
                        if (c > 0) {
                            line.append(" | ");
                        }
                        line.append(value);
                    }
                    if (!line.toString().isBlank()) {
                        sb.append(line).append('\n');
                    }
                }
                if (sb.length() > ("【Sheet: " + sheet.getSheetName() + "】\n").length()) {
                    segments.add(new TextSegment(sb.toString().trim(), "Sheet:" + sheet.getSheetName()));
                }
            }
        }
        return segments;
    }
}
