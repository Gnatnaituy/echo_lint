package com.echolint.service.rag.doc;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX 文本抽取器（RAG Phase D），基于 POI 5.3.0 XWPF。
 *
 * <p>按正文顺序抽取段落与表格：段落之间换行；表格按行输出，同一行单元格用 {@code " | "} 连接，
 * 单元格内多个段落用空格拼接并展平为单行，避免破坏“一行一条记录”的结构。页眉页脚不抽取
 * （通常为页码/标题等重复噪声）。损坏文件（POI 的 {@code NotOfficeXmlFileException} /
 * {@code EmptyFileException} 等）异常向上抛出。</p>
 */
@Component
public class DocxDocumentTextExtractor implements DocumentTextExtractor {

    private static final String SUPPORTED_EXTENSION = "docx";
    /** 表格同一行单元格之间的连接符 */
    private static final String CELL_SEPARATOR = " | ";

    @Override
    public boolean supports(String extension) {
        return SUPPORTED_EXTENSION.equalsIgnoreCase(extension);
    }

    @Override
    public String extract(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("DOCX 输入流不能为 null");
        }
        // 先读入内存：XWPFDocument 从 InputStream 构造，解析后关闭文档对象时不影响调用方流
        byte[] bytes = in.readAllBytes();
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                switch (element.getElementType()) {
                    case PARAGRAPH -> appendParagraph(text, (XWPFParagraph) element);
                    case TABLE -> appendTable(text, (XWPFTable) element);
                    default -> {
                        // 内容控件等暂不抽取
                    }
                }
            }
        }
        return DocumentTextNormalizer.normalize(text.toString());
    }

    /** 追加一个段落（段落之间以换行分隔，空段落丢弃） */
    private static void appendParagraph(StringBuilder target, XWPFParagraph paragraph) {
        String line = DocumentTextNormalizer.normalize(paragraph.getText());
        if (!line.isEmpty()) {
            target.append(line).append('\n');
        }
    }

    /** 追加整张表格：一行输出一条，单元格用 " | " 连接 */
    private static void appendTable(StringBuilder target, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellText(cell));
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            target.append(String.join(CELL_SEPARATOR, cells)).append('\n');
        }
    }

    /** 单元格内多个段落用空格连接并展平为单行 */
    private static String cellText(XWPFTableCell cell) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String line = DocumentTextNormalizer.singleLine(paragraph.getText());
            if (line.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(line);
        }
        return text.toString();
    }
}
