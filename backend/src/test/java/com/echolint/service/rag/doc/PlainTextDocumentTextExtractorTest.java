package com.echolint.service.rag.doc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯文本抽取器单测：UTF-8 中文、BOM、GB18030 兜底、空文件、扩展名路由。
 */
class PlainTextDocumentTextExtractorTest {

    private final PlainTextDocumentTextExtractor extractor = new PlainTextDocumentTextExtractor();

    @Test
    void supportsTxtAndMarkdownOnly() {
        assertTrue(extractor.supports("txt"));
        assertTrue(extractor.supports("md"));
        assertTrue(extractor.supports("MD"), "扩展名大小写不敏感");
        assertFalse(extractor.supports("pdf"), "pdf 应由 PdfDocumentTextExtractor 处理");
        assertFalse(extractor.supports("docx"));
        assertFalse(extractor.supports(null), "null 扩展名不应报错");
    }

    @Test
    void readsUtf8ChineseAndNormalizesNewlines() throws Exception {
        byte[] bytes = "第一行中文\r\n第二行中文\r第三行".getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(new ByteArrayInputStream(bytes));

        assertEquals("第一行中文\n第二行中文\n第三行", text);
    }

    @Test
    void stripsUtf8Bom() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        raw.write("带 BOM 的中文内容".getBytes(StandardCharsets.UTF_8));

        String text = extractor.extract(new ByteArrayInputStream(raw.toByteArray()));

        assertEquals("带 BOM 的中文内容", text);
        assertFalse(text.startsWith("\uFEFF"), "BOM 不应残留到结果中");
    }

    @Test
    void fallsBackToGb18030ForNonUtf8Bytes() throws Exception {
        Charset gb18030 = Charset.forName("GB18030");
        // "你好世界" 的 GB18030 字节不是合法 UTF-8，应走 GB18030 兜底
        byte[] bytes = "你好世界".getBytes(gb18030);

        String text = extractor.extract(new ByteArrayInputStream(bytes));

        assertEquals("你好世界", text);
    }

    @Test
    void emptyFileReturnsEmptyString() throws Exception {
        assertEquals("", extractor.extract(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void whitespaceOnlyFileReturnsEmptyString() throws Exception {
        byte[] bytes = " \r\n\t \n".getBytes(StandardCharsets.UTF_8);
        assertEquals("", extractor.extract(new ByteArrayInputStream(bytes)));
    }

    @Test
    void removesControlCharsButKeepsTabAndNewline() throws Exception {
        byte[] bytes = "a\u0000b\tc\nd\u0007".getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract(new ByteArrayInputStream(bytes));

        assertEquals("ab\tc\nd", text);
    }

    @Test
    void doesNotCloseCallerStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream in = new ByteArrayInputStream("EchoLint".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        extractor.extract(in);

        assertFalse(closed.get(), "抽取器不应关闭调用方输入流");
    }
}
