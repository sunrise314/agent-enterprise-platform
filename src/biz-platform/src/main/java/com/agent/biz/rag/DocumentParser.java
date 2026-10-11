package com.agent.biz.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档解析器：支持 PDF / Word(.docx) / TXT 三种企业常用格式。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「文档解析」。
 * 设计：按扩展名分发到具体解析器；解析结果统一为纯文本，交由 {@link SemanticChunker} 分块。
 */
@Component
public class DocumentParser {

    /** 解析上传文件为纯文本。 */
    public String parse(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        byte[] bytes = file.getBytes();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            if (lower.endsWith(".pdf")) {
                return parsePdf(in);
            }
            if (lower.endsWith(".docx")) {
                return parseDocx(in);
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            throw new IOException("不支持的文件类型: " + name + "（支持 pdf / docx / txt）");
        }
    }

    private String parsePdf(InputStream in) throws IOException {
        try (PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String parseDocx(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
            // 表格段落补充
            doc.getTables().forEach(t -> t.getRows().forEach(row ->
                    row.getTableCells().forEach(cell ->
                            cell.getParagraphs().forEach(p -> {
                                String text = p.getText();
                                if (text != null && !text.isBlank()) {
                                    sb.append(text).append('\n');
                                }
                            }))));
            return sb.toString();
        }
    }
}
