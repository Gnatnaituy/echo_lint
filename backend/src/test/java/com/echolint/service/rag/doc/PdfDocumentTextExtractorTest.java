package com.echolint.service.rag.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PDF 抽取器单测：内存生成 PDF 后抽取、损坏字节抛错、不关闭调用方流。
 */
class PdfDocumentTextExtractorTest {

    private final PdfDocumentTextExtractor extractor = new PdfDocumentTextExtractor();

    @Test
    void supportsPdfOnly() {
        assertTrue(extractor.supports("pdf"));
        assertTrue(extractor.supports("PDF"), "扩展名大小写不敏感");
        assertFalse(extractor.supports("docx"));
        assertFalse(extractor.supports(null), "null 扩展名不应报错");
    }

    @Test
    void extractsTextFromGeneratedPdf() throws Exception {
        byte[] pdf = createPdf("Hello EchoLint PDF");

        String text = extractor.extract(new ByteArrayInputStream(pdf));

        assertTrue(text.contains("Hello EchoLint PDF"), () -> "实际抽取内容：" + text);
    }

    @Test
    void normalizesNewlinesAndDropsControlChars() throws Exception {
        // PDFBox 抽出的文本不应残留 \r 等控制字符
        String text = extractor.extract(new ByteArrayInputStream(createPdf("EchoLint")));

        assertFalse(text.contains("\r"), () -> "实际抽取内容：" + text);
        assertFalse(text.startsWith("\n"), "首尾空白应被裁剪");
    }

    @Test
    void doesNotCloseCallerStream() throws Exception {
        byte[] pdf = createPdf("Hello EchoLint PDF");
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream in = new ByteArrayInputStream(pdf) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        extractor.extract(in);

        assertFalse(closed.get(), "抽取器不应关闭调用方输入流");
    }

    @Test
    void corruptBytesThrowException() {
        byte[] corrupt = "这不是一个 PDF 文件".getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> extractor.extract(new ByteArrayInputStream(corrupt)));
    }

    /** 用 PDFBox 在内存生成单页 PDF（Helvetica 标准字体仅支持 ASCII，测试文本用英文） */
    private static byte[] createPdf(String content) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 700);
                stream.showText(content);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
