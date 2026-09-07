package com.company.qagent;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;

/**
 * 测试用工具：生成一份模拟"公司员工手册"的中文 PDF，用于验证文档上传入库链路。
 * 运行方式（在项目根目录）：mvn exec:java -Dexec.mainClass=com.company.qagent.CreateTestPdf
 */
public class CreateTestPdf {

    public static void main(String[] args) throws Exception {
        // 中文需要加载字体（否则 PDF 里中文显示为乱码/方块）
        // 注意：PDFBox 不能直接加载 .ttc（字体集合），必须用单个 .ttf
        String fontPath = null;
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",  // 黑体（单 ttf）
                "C:/Windows/Fonts/simsun.ttc",  // 宋体（ttc，可能不兼容）
                "C:/Windows/Fonts/msyh.ttf"     // 微软雅黑
        };
        for (String c : candidates) {
            if (new File(c).exists()) {
                fontPath = c;
                break;
            }
        }
        if (fontPath == null) {
            System.err.println("未找到中文字体，PDF 中文会显示异常");
        }

        try (PDDocument doc = new PDDocument()) {
            // 加载中文字体
            PDType0Font font;
            if (fontPath != null) {
                font = PDType0Font.load(doc, new File(fontPath));
            } else {
                // 没有中文字体时的兜底（中文会显示为方块，但内容仍在）
                font = PDType0Font.load(doc, new File("C:/Windows/Fonts/simsun.ttc"));
            }

            // 第 1 页：员工报销制度
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(60, 750);
                cs.showText("员工手册 - 报销制度");
                cs.newLineAtOffset(0, -40);
                cs.setFont(font, 11);
                cs.showText("第一章 费用报销总则");
                cs.newLineAtOffset(0, -28);
                cs.showText("1.1 适用范围：本制度适用于公司全体员工。");
                cs.newLineAtOffset(0, -28);
                cs.showText("1.2 员工因公产生的费用，需在发生后 30 天内提交报销申请。");
                cs.newLineAtOffset(0, -28);
                cs.showText("1.3 报销须填写《费用报销单》，并附上原始票据。");
                cs.newLineAtOffset(0, -28);
                cs.showText("1.4 报销单据经部门主管审批后，交由财务部审核。");
                cs.newLineAtOffset(0, -28);
                cs.showText("1.5 财务部审核通过后，报销款项将在 7 个工作日内到账。");
                cs.endText();
            }

            // 第 2 页：请假制度
            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(60, 750);
                cs.showText("员工手册 - 请假制度");
                cs.newLineAtOffset(0, -40);
                cs.setFont(font, 11);
                cs.showText("第二章 请假管理规定");
                cs.newLineAtOffset(0, -28);
                cs.showText("2.1 员工请假须提前一天向直属主管提出申请。");
                cs.newLineAtOffset(0, -28);
                cs.showText("2.2 病假超过 3 天需提供医院开具的病假证明。");
                cs.newLineAtOffset(0, -28);
                cs.showText("2.3 年假按入职年限计算，每年 5 至 15 天。");
                cs.newLineAtOffset(0, -28);
                cs.showText("2.4 事假每月累计不得超过 5 天。");
                cs.endText();
            }

            // 保存到项目根目录
            File out = new File("test-sample.pdf");
            doc.save(out);
            System.out.println("已生成测试文件: " + out.getAbsolutePath());
        }
    }
}
