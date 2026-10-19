package com.echolint.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 CSV 解析工具（不引第三方依赖），支持：
 * <ul>
 *   <li>双引号包裹的字段，字段内可含逗号、换行；{@code ""} 表示一个字面双引号；</li>
 *   <li>CRLF / LF / CR 三种换行；</li>
 *   <li>文件开头的 UTF-8 BOM 自动忽略。</li>
 * </ul>
 *
 * <p>空行会保留为「只有一个空字段的行」，由调用方决定忽略。</p>
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        String text = content.charAt(0) == '\uFEFF' ? content.substring(1) : content;

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && !fieldStarted && field.length() == 0) {
                inQuotes = true;
                fieldStarted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\n') {
                row.add(field.toString());
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r') {
                // 兼容 CRLF：统一按换行处理，紧随的 \n 直接跳过
                row.add(field.toString());
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        // 最后一行没有换行符时收尾；空文件/末尾换行不额外产生空行
        if (field.length() > 0 || !row.isEmpty() || inQuotes) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
