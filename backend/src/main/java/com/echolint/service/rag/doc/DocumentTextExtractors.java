package com.echolint.service.rag.doc;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 文档文本抽取器注册表：按扩展名路由到具体实现（PDF / DOCX / 纯文本）。
 */
@Component
public class DocumentTextExtractors {

    private final List<DocumentTextExtractor> extractors;

    public DocumentTextExtractors(List<DocumentTextExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    /** 按扩展名查找抽取器（小写、不含点） */
    public Optional<DocumentTextExtractor> find(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String ext = extension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return extractors.stream().filter(e -> e.supports(ext)).findFirst();
    }

    /** 已注册的扩展名（用于配置校验/日志） */
    public List<String> supportedExtensions() {
        return extractors.stream()
                .map(e -> e.getClass().getSimpleName())
                .toList();
    }
}
