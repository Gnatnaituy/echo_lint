package com.echolint.service.rag.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF 文本抽取器（RAG Phase D），基于 PDFBox 2.0.31。
 *
 * <p>PDFBox 2.x 的 {@link PDDocument#load(byte[])} 需要 byte[] / File：这里先把调用方流一次性
 * 读入内存再加载，既满足 API 要求，也保证关闭 PDDocument 时不会反向关闭调用方流。加密 PDF
 * （{@code InvalidPasswordException}）与损坏文件（{@code IOException}）等异常直接向上抛出，
 * 由上层把知识库条目置为 FAILED。</p>
 */
@Component
public class PdfDocumentTextExtractor implements DocumentTextExtractor {

    private static final String SUPPORTED_EXTENSION = "pdf";

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
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按坐标排序，尽量还原多栏排版与绝对定位文本的阅读顺序
            stripper.setSortByPosition(true);
            // PDFBox 2 的默认行分隔符取操作系统值，这里固定为 \n 便于跨平台一致
            stripper.setLineSeparator("\n");
            return DocumentTextNormalizer.normalize(stripper.getText(document));
        }
    }
}
