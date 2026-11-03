package com.echolint.service.rag.doc;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * PDF 文本抽取器（RAG Phase D），基于 PDFBox 2.0.31。
 *
 * <p>PDFBox 2.x 的 {@link PDDocument#load(byte[])} 需要 byte[] / File：这里先把调用方流一次性 读入内存再加载，既满足 API
 * 要求，也保证关闭 PDDocument 时不会反向关闭调用方流。加密 PDF （{@code InvalidPasswordException}）与损坏文件（{@code
 * IOException}）等异常直接向上抛出， 由上层把知识库条目置为 FAILED。
 *
 * <p>内存保护（审计 P2-14）：
 *
 * <ul>
 *   <li>解析前限制 PDF 字节数（{@value #MAX_COMPRESSED_BYTES}）与页数（{@value #MAX_PAGES}）， 避免超大文件长时间占用 CPU/内存；
 *   <li>用 {@link MemoryUsageSetting#setupMixed(long)} 限定主存缓冲，超出部分落临时文件， 避免整本 PDF 及解析中间结构全部驻留堆内存；
 *   <li>自定义 {@link PDFTextStripper}：累计字符数超过 {@link DocumentTextExtractor#MAX_TEXT_CHARS}
 *       立即抛异常终止，而不是把全文抽完再截断。
 * </ul>
 */
@Component
public class PdfDocumentTextExtractor implements DocumentTextExtractor {

    private static final String SUPPORTED_EXTENSION = "pdf";

    /** 解析前字节上限（与 RagDocumentService 的解析上限保持一致） */
    private static final int MAX_COMPRESSED_BYTES = 20 * 1024 * 1024;

    /** 页数上限：500 页纯文本 PDF 已远超单条知识库条目容量，再大应拆分导入 */
    private static final int MAX_PAGES = 500;

    /** PDFBox 主内存缓冲上限：超出部分落临时文件（setupMixed） */
    private static final long MAIN_MEMORY_BYTES = 16L * 1024 * 1024;

    @Override
    public boolean supports(String extension) {
        return SUPPORTED_EXTENSION.equalsIgnoreCase(extension);
    }

    @Override
    public String extract(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("PDF 输入流不能为 null");
        }
        // 注意：只读入内存，不关闭入参流（由调用方负责关闭）
        byte[] bytes = in.readAllBytes();
        if (bytes.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("PDF 超过单次解析大小上限（" + (MAX_COMPRESSED_BYTES / 1024 / 1024) + "MB）");
        }
        // PDFBox 2.0.x 只有带 password/keyStore 的重载才接受 MemoryUsageSetting：
        // 空口令 + 无密钥库，同时把主存缓冲限制在 16MB（超出落临时文件），避免整本 PDF 驻留堆。
        try (PDDocument document =
                PDDocument.load(
                        bytes, "", null, null, MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES))) {
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new IOException("PDF 页数 " + pages + " 超过单次解析上限（" + MAX_PAGES + " 页），请拆分后导入");
            }
            PDFTextStripper stripper = new LimitedPDFTextStripper();
            // 按坐标排序，尽量还原多栏排版与绝对定位文本的阅读顺序
            stripper.setSortByPosition(true);
            // PDFBox 2 的默认行分隔符取操作系统值，这里固定为 \n 便于跨平台一致
            stripper.setLineSeparator("\n");
            return DocumentTextNormalizer.normalize(stripper.getText(document));
        }
    }

    /**
     * 带字符上限的文本抽取器：一旦超过上限直接抛异常终止解析。
     *
     * <p>PDF 是流式抽取，无法像 DOCX 那样在拼装阶段精确截断；这里选择「超限即失败」 而不是静默截断，避免用户误以为整份文档已入库（失败原因会写入 index_error）。
     */
    private static final class LimitedPDFTextStripper extends PDFTextStripper {

        private int chars;

        private LimitedPDFTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            int add = text == null ? 0 : text.length();
            if (chars + add > DocumentTextExtractor.MAX_TEXT_CHARS) {
                throw new IOException(
                        "PDF 文本超过单次解析上限（" + DocumentTextExtractor.MAX_TEXT_CHARS + " 字符），请拆分后导入");
            }
            chars += add;
            super.writeString(text, textPositions);
        }
    }
}
