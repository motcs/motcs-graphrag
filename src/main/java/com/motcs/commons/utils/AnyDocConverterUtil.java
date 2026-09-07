package com.motcs.commons.utils;

import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * 统一文档转换工具类
 * 将各种格式文件统一转换为Markdown格式
 * 支持：PDF、Word、Excel、PowerPoint、TXT、MD等格式
 * 新增：直接输出 spring‑ai Document 对象
 */
@Log4j2
@Component
public class AnyDocConverterUtil {

    /**
     * 将文件转换为Markdown格式
     *
     * @param filePath 文件路径
     * @return Markdown内容
     * @throws IOException IO异常
     */
    public String convertToMarkdown(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + filePath);
        }

        String fileName = filePath.getFileName().toString();
        String fileExtension = Utils.getFileExtension(fileName).toLowerCase();

        log.info("开始转换文件为Markdown: fileName={}, extension={}", fileName, fileExtension);

        return switch (fileExtension) {
            case "pdf" -> convertPdfToMarkdown(filePath);
            case "doc", "docx" -> convertWordToMarkdown(filePath);
            case "xls", "xlsx" -> convertExcelToMarkdown(filePath);
            case "ppt", "pptx" -> convertPowerPointToMarkdown(filePath);
            case "txt", "csv" -> convertTextToMarkdown(filePath);
            case "md" -> convertMdToMarkdown(filePath);
            default -> throw new IOException("不支持的文件格式: " + fileExtension);
        };
    }

    /**
     * 将文件字节转换为Markdown格式
     *
     * @param fileName  文件名
     * @param fileBytes 文件字节数组
     * @return Markdown内容
     * @throws IOException IO异常
     */
    public String convertToMarkdown(String fileName, byte[] fileBytes) throws IOException {
        // 创建临时文件
        Path tempFile = Files.createTempFile("any_doc_", "_" + fileName);
        try {
            Files.write(tempFile, fileBytes);
            return convertToMarkdown(tempFile);
        } finally {
            // 删除临时文件
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 将PDF转换为Markdown（按页提取，每页插入 [[PAGE:n]] 标记）
     */
    private String convertPdfToMarkdown(Path filePath) throws IOException {
        log.debug("开始转换PDF文件: {}", filePath);

        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder markdown = new StringBuilder();
            markdown.append("# 文档内容\n\n");

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                if (!pageText.isEmpty()) {
                    markdown.append("[[PAGE:").append(page).append("]]\n\n");
                    markdown.append(pageText).append("\n\n");
                }
            }

            log.debug("PDF转换完成，共 {} 页，内容长度: {}", pageCount, markdown.length());
            return markdown.toString();
        }
    }

    /**
     * 将Word文档转换为Markdown
     */
    private String convertWordToMarkdown(Path filePath) throws IOException {
        log.debug("开始转换Word文档: {}", filePath);

        String extension = Utils.getFileExtension(filePath.getFileName().toString()).toLowerCase();
        String markdown;

        if (extension.equals("doc")) {
            // 处理旧版Word格式 (.doc)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 HWPFDocument document = new HWPFDocument(fis);
                 WordExtractor extractor = new WordExtractor(document)) {

                String text = extractor.getText();
                markdown = convertTextToMarkdown(text);
            }
        } else {
            // 处理新版Word格式 (.docx)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 XWPFDocument document = new XWPFDocument(fis)) {

                StringBuilder content = new StringBuilder();
                // 仅用 document.getParagraphs() 会漏掉表格内的全部文本（工作类文档
                // 内容常在表格中）。按文档顺序遍历 body 元素：段落与表格交错出现。
                for (var element : document.getBodyElements()) {
                    if (element instanceof XWPFParagraph paragraph) {
                        appendParagraphText(content, paragraph);
                    } else if (element instanceof XWPFTable table) {
                        appendTableText(content, table);
                    }
                }

                markdown = content.toString();
            }
        }

        log.debug("Word转换完成，内容长度: {}", markdown.length());
        return markdown;
    }

    /**
     * 追加段落文本（简单标题识别）
     */
    private void appendParagraphText(StringBuilder content, XWPFParagraph paragraph) {
        String text = paragraph.getText().trim();
        if (!text.isEmpty()) {
            if (isHeading(text)) {
                content.append("## ").append(text).append("\n\n");
            } else {
                content.append(text).append("\n\n");
            }
        }
    }

    /**
     * 递归追加表格文本（含嵌套表格）：工作类 docx 内容常在表格单元格内
     */
    private void appendTableText(StringBuilder content, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText().trim();
                if (!cellText.isEmpty()) {
                    content.append(cellText).append("\n\n");
                }
                // 嵌套表格（单元格内再嵌表格）
                for (XWPFTable nested : cell.getTables()) {
                    appendTableText(content, nested);
                }
            }
        }
    }

    /**
     * 将Excel转换为Markdown
     */
    private String convertExcelToMarkdown(Path filePath) throws IOException {
        log.debug("开始转换Excel文件: {}", filePath);

        String extension = Utils.getFileExtension(filePath.getFileName().toString()).toLowerCase();
        StringBuilder markdown = new StringBuilder();

        if (extension.equals("xls")) {
            // 处理旧版Excel格式 (.xls)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 HSSFWorkbook workbook = new HSSFWorkbook(fis)) {

                for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                    var sheet = workbook.getSheetAt(sheetIndex);
                    markdown.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                    for (Row row : sheet) {
                        rowMarkdownAppend(markdown, row);
                    }
                    markdown.append("\n");
                }
            }
        } else {
            // 处理新版Excel格式 (.xlsx)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

                for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                    var sheet = workbook.getSheetAt(sheetIndex);
                    markdown.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                    for (org.apache.poi.ss.usermodel.Row row : sheet) {
                        rowMarkdownAppend(markdown, row);
                    }
                    markdown.append("\n");
                }
            }
        }

        log.debug("Excel转换完成，内容长度: {}", markdown.length());
        return markdown.toString();
    }

    private void rowMarkdownAppend(StringBuilder markdown, Row row) {
        var cells = row.iterator();
        StringBuilder rowMarkdown = new StringBuilder("| ");

        while (cells.hasNext()) {
            var cell = cells.next();
            String cellValue = getCellValueAsString(cell);
            rowMarkdown.append(cellValue).append(" | ");
        }

        markdown.append(rowMarkdown).append("\n");
    }

    /**
     * 将PowerPoint转换为Markdown
     */
    private String convertPowerPointToMarkdown(Path filePath) throws IOException {
        log.debug("开始转换PowerPoint文件: {}", filePath);

        String extension = Utils.getFileExtension(filePath.getFileName().toString()).toLowerCase();
        StringBuilder markdown = new StringBuilder();

        if (extension.equals("ppt")) {
            // 处理旧版PowerPoint格式 (.ppt)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 HSLFSlideShow ppt = new HSLFSlideShow(fis)) {

                List<HSLFSlide> slides = ppt.getSlides();
                for (int i = 0; i < slides.size(); i++) {
                    markdown.append("## Slide ").append(i + 1).append("\n\n");

                    // 提取文本内容
                    String slideText = slides.get(i).getTextParagraphs().stream()
                            .flatMap(Collection::stream)
                            .flatMap(v -> v.getTextRuns()
                                    .stream().map(HSLFTextRun::getRawText))
                            .filter(text -> !text.trim().isEmpty())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    markdown.append(slideText).append("\n\n");
                }
            }
        } else {
            // 处理新版PowerPoint格式 (.pptx)
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 XMLSlideShow ppt = new XMLSlideShow(fis)) {

                List<XSLFSlide> slides = ppt.getSlides();
                for (int i = 0; i < slides.size(); i++) {
                    markdown.append("## Slide ").append(i + 1).append("\n\n");

                    // 提取文本内容
                    String slideText = slides.get(i).getShapes().stream()
                            .filter(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape)
                            .map(shape -> (org.apache.poi.xslf.usermodel.XSLFTextShape) shape)
                            .flatMap(textShape -> textShape.getTextParagraphs().stream())
                            .flatMap(paragraph -> paragraph.getTextRuns().stream())
                            .map(XSLFTextRun::getRawText)
                            .filter(text -> !text.trim().isEmpty())
                            .collect(java.util.stream.Collectors.joining("\n"));

                    markdown.append(slideText).append("\n\n");
                }
            }
        }

        log.debug("PowerPoint转换完成，内容长度: {}", markdown.length());
        return markdown.toString();
    }

    /**
     * 将纯文本转换为Markdown
     */
    private String convertTextToMarkdown(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        return convertTextToMarkdown(content);
    }

    /**
     * 将纯文本内容转换为Markdown
     */
    public String convertTextToMarkdown(String content) {
        StringBuilder markdown = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty()) {
                markdown.append(trimmedLine).append("\n\n");
            }
        }

        return markdown.toString();
    }

    /**
     * 将Markdown文件转换为Markdown（直接返回内容）
     */
    private String convertMdToMarkdown(Path filePath) throws IOException {
        return Files.readString(filePath);
    }

    /**
     * 判断文本是否为标题
     */
    private boolean isHeading(String text) {
        // 简单的标题判断逻辑
        return text.length() < 100 && !text.contains(".") && text.matches(".*[A‑Z].*");
    }

    /**
     * 获取Excel单元格值作为字符串
     */
    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

}
