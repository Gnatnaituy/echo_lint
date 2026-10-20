package com.echolint.service.rag.doc;

/**
 * 抽取文本的通用规范化工具（包内可见，供各抽取器复用）。
 *
 * <p>统一把换行符归一为 {@code \n}，丢弃除换行符、制表符以外的 ISO 控制字符，
 * 并去除首尾空白；null / 纯空白文本统一返回空字符串。</p>
 */
final class DocumentTextNormalizer {

    private DocumentTextNormalizer() {
    }

    /**
     * 规范化多行文本。
     *
     * @param raw 原始文本（可为 null）
     * @return 规范化后的文本（不为 null，无有效内容时为空字符串）
     */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            // 保留换行/制表符，其余控制字符（\u0000 等）丢弃
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.toString().strip();
    }

    /**
     * 展平为单行文本：供表格单元格使用，避免单元格内换行打断“一行一条记录”的结构。
     *
     * @param raw 原始文本（可为 null）
     * @return 单行文本（连续空白压缩为一个空格，去首尾空白）
     */
    static String singleLine(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return "";
        }
        // 普通空白之外，一并压缩不间断空格（\u00A0）与全角空格（\u3000）
        return normalized.replaceAll("[\\s\\u00A0\\u3000]+", " ").strip();
    }
}
