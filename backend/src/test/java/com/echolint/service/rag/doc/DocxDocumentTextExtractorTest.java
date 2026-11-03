package com.echolint.service.rag.doc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/** DOCX 抽取器单测：内存生成含段落与表格的 docx 后抽取、损坏字节抛错、不关闭调用方流。 */
class DocxDocumentTextExtractorTest {

    private final DocxDocumentTextExtractor extractor = new DocxDocumentTextExtractor();

    @Test
    void supportsDocxOnly() {
        assertTrue(extractor.supports("docx"));
        assertTrue(extractor.supports("DOCX"), "扩展名大小写不敏感");
        assertFalse(extractor.supports("pdf"));
        assertFalse(extractor.supports(null), "null 扩展名不应报错");
    }

    @Test
    void extractsParagraphsAndTableCellsInOrder() throws Exception {
        byte[] docx = createDocx();

        String text = extractor.extract(new ByteArrayInputStream(docx));

        assertTrue(text.contains("第一段 EchoLint"), () -> "实际抽取内容：" + text);
        assertTrue(text.contains("第二段内容"), () -> "实际抽取内容：" + text);
        assertTrue(text.contains("姓名 | 张三"), () -> "表格行应按 ' | ' 连接单元格：\n" + text);
        assertTrue(text.contains("年龄 | 30"), () -> "表格行应按 ' | ' 连接单元格：\n" + text);
        assertTrue(text.indexOf("第一段 EchoLint") < text.indexOf("姓名 | 张三"), "应保持正文顺序");
    }

    @Test
    void doesNotCloseCallerStream() throws Exception {
        byte[] docx = createDocx();
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream in =
                new ByteArrayInputStream(docx) {
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
        byte[] corrupt = "这不是一个 DOCX 文件".getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> extractor.extract(new ByteArrayInputStream(corrupt)));
    }

    /** 用 POI 在内存生成 docx：两个段落 + 一张 2x2 表格 */
    private static byte[] createDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("第一段 EchoLint");
            document.createParagraph().createRun().setText("第二段内容");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("姓名");
            table.getRow(0).getCell(1).setText("张三");
            table.getRow(1).getCell(0).setText("年龄");
            table.getRow(1).getCell(1).setText("30");

            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void truncatesHugeDocumentAtExtractorLimit() throws Exception {
        byte[] docx = createLongDocx(DocumentTextExtractor.MAX_TEXT_CHARS * 2);

        String text = extractor.extract(new ByteArrayInputStream(docx));

        assertEquals(DocumentTextExtractor.MAX_TEXT_CHARS, text.length(), "抽取应在上限处提前截断");
    }

    @Test
    void rejectsOversizedCompressedDocumentBeforeParsing() {
        // 只验证字节上限分支：超过 20MB 的输入在解压/解析前直接拒绝（内容不必是合法 zip）
        byte[] huge = new byte[20 * 1024 * 1024 + 1];

        IOException error =
                assertThrows(
                        IOException.class, () -> extractor.extract(new ByteArrayInputStream(huge)));

        assertTrue(error.getMessage().contains("上限"), error.getMessage());
    }

    /** 单段落超长 docx：验证抽取阶段就截断，而不是拼完 20 万字再截 */
    private static byte[] createLongDocx(int chars) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 用伪随机字母而不是重复字符：重复内容压缩比过高会触发 POI 解压炸弹阈值（minInflateRatio=0.01），
            // 那是安全守卫的预期行为，不是本测试要验证的路径。
            java.util.Random random = new java.util.Random(42);
            StringBuilder content = new StringBuilder(chars);
            for (int i = 0; i < chars; i++) {
                content.append((char) ('a' + random.nextInt(26)));
            }
            document.createParagraph().createRun().setText(content.toString());
            document.write(out);
            return out.toByteArray();
        }
    }
}
