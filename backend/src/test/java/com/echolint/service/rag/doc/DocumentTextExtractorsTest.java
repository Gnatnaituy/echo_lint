package com.echolint.service.rag.doc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抽取器注册表单测：扩展名路由（大小写/点号）、未知扩展名返回空、端到端抽取可走通。
 */
class DocumentTextExtractorsTest {

    private final DocumentTextExtractors registry = new DocumentTextExtractors(List.of(
            new PdfDocumentTextExtractor(),
            new DocxDocumentTextExtractor(),
            new PlainTextDocumentTextExtractor()));

    @Test
    void findRoutesPdfByExtensionIgnoringCase() {
        assertInstanceOf(PdfDocumentTextExtractor.class, registry.find("PDF").orElseThrow());
        assertInstanceOf(PdfDocumentTextExtractor.class, registry.find(".pdf").orElseThrow());
    }

    @Test
    void findRoutesDocxAndPlainText() {
        assertInstanceOf(DocxDocumentTextExtractor.class, registry.find("docx").orElseThrow());
        assertInstanceOf(PlainTextDocumentTextExtractor.class, registry.find("txt").orElseThrow());
        assertInstanceOf(PlainTextDocumentTextExtractor.class, registry.find("md").orElseThrow());
    }

    @Test
    void findUnknownOrNullIsEmpty() {
        assertTrue(registry.find("unknown").isEmpty());
        assertTrue(registry.find(null).isEmpty(), "null 扩展名应返回空");
    }

    @Test
    void routedExtractorExtractsPlainText() throws Exception {
        DocumentTextExtractor plainText = registry.find("txt").orElseThrow();

        String text = plainText.extract(new ByteArrayInputStream("你好 EchoLint".getBytes(StandardCharsets.UTF_8)));

        assertEquals("你好 EchoLint", text);
    }

    @Test
    void supportedExtensionsListsAllImplementations() {
        List<String> names = registry.supportedExtensions();

        assertEquals(3, names.size());
        assertTrue(names.containsAll(List.of(
                "PdfDocumentTextExtractor",
                "DocxDocumentTextExtractor",
                "PlainTextDocumentTextExtractor")));
    }
}
