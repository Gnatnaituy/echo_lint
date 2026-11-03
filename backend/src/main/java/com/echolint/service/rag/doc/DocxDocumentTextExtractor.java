package com.echolint.service.rag.doc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * DOCX 文本抽取器（RAG Phase D），基于 POI 5.3.0 XWPF。
 *
 * <p>按正文顺序抽取段落与表格：段落之间换行；表格按行输出，同一行单元格用 {@code " | "} 连接，
 * 单元格内多个段落用空格拼接并展平为单行，避免破坏“一行一条记录”的结构。页眉页脚不抽取 （通常为页码/标题等重复噪声）。损坏文件（POI 的 {@code
 * NotOfficeXmlFileException} / {@code EmptyFileException} 等）异常向上抛出。
 *
 * <p>内存保护（审计 P2-14）：
 *
 * <ul>
 *   <li>解析前限制压缩包字节数，避免超大文档进入解析；
 *   <li>POI 解压炸弹守卫：{@link ZipSecureFile#setMinInflateRatio(double)}（压缩比下限）、 {@link
 *       ZipSecureFile#setMaxEntrySize(long)}（单条目解压上限）、 {@link
 *       IOUtils#setByteArrayMaxOverride(int)}（POI 单次 byte[] 分配上限）；
 *   <li>抽取过程中一旦达到 {@link DocumentTextExtractor#MAX_TEXT_CHARS} 立即停止， 不再把超长正文完整拼进 StringBuilder。
 * </ul>
 */
@Component
public class DocxDocumentTextExtractor implements DocumentTextExtractor {

    private static final String SUPPORTED_EXTENSION = "docx";

    /** 表格同一行单元格之间的连接符 */
    private static final String CELL_SEPARATOR = " | ";

    /** 解析前压缩包大小上限（与 RagDocumentService 的解析上限保持一致） */
    private static final int MAX_COMPRESSED_BYTES = 20 * 1024 * 1024;

    /** 解压炸弹守卫：单个 zip 条目解压后的最大体积 */
    private static final long MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024;

    /** POI 单次 byte[] 分配上限（防止恶意文档让 POI 一次申请超大数组） */
    private static final int MAX_POI_ARRAY_BYTES = 64 * 1024 * 1024;

    /** 压缩比下限：解压后体积 / 压缩体积，低于该比例视为解压炸弹（POI 默认也是 0.01） */
    private static final double MIN_INFLATE_RATIO = 0.01;

    static {
        // 静态全局设置，进程内对所有 POI 解析生效；本项目只有文档导入使用 POI。
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        IOUtils.setByteArrayMaxOverride(MAX_POI_ARRAY_BYTES);
    }

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
        if (bytes.length > MAX_COMPRESSED_BYTES) {
            throw new IOException(
                    "DOCX 超过单次解析大小上限（" + (MAX_COMPRESSED_BYTES / 1024 / 1024) + "MB）");
        }
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (text.length() >= DocumentTextExtractor.MAX_TEXT_CHARS) {
                    break;
                }
                switch (element.getElementType()) {
                    case PARAGRAPH -> appendParagraph(text, (XWPFParagraph) element);
                    case TABLE -> appendTable(text, (XWPFTable) element);
                    default -> {
                        // 内容控件等暂不抽取
                    }
                }
            }
        }
        return DocumentTextNormalizer.normalize(limit(text.toString()));
    }

    /** 追加一个段落（段落之间以换行分隔，空段落丢弃） */
    private static void appendParagraph(StringBuilder target, XWPFParagraph paragraph) {
        String line = DocumentTextNormalizer.normalize(paragraph.getText());
        if (!line.isEmpty()) {
            appendLimited(target, line);
            if (target.length() < DocumentTextExtractor.MAX_TEXT_CHARS) {
                target.append('\n');
            }
        }
    }

    /** 追加整张表格：一行输出一条，单元格用 " | " 连接 */
    private static void appendTable(StringBuilder target, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            if (target.length() >= DocumentTextExtractor.MAX_TEXT_CHARS) {
                return;
            }
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellText(cell));
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            appendLimited(target, String.join(CELL_SEPARATOR, cells));
            if (target.length() < DocumentTextExtractor.MAX_TEXT_CHARS) {
                target.append('\n');
            }
        }
    }

    /** 单元格内多个段落用空格连接并展平为单行（同样受总字符上限约束） */
    private static String cellText(XWPFTableCell cell) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            if (text.length() >= DocumentTextExtractor.MAX_TEXT_CHARS) {
                break;
            }
            String line = DocumentTextNormalizer.singleLine(paragraph.getText());
            if (line.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            appendLimited(text, line);
        }
        return text.toString();
    }

    /** 只追加剩余额度内的字符，超出部分直接丢弃（提前截断，避免拼接超大字符串） */
    private static void appendLimited(StringBuilder target, String value) {
        int remaining = DocumentTextExtractor.MAX_TEXT_CHARS - target.length();
        if (remaining <= 0 || value == null || value.isEmpty()) {
            return;
        }
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private static String limit(String value) {
        return value.length() <= DocumentTextExtractor.MAX_TEXT_CHARS
                ? value
                : value.substring(0, DocumentTextExtractor.MAX_TEXT_CHARS);
    }
}
