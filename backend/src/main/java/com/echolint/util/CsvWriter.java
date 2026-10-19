package com.echolint.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 极简 CSV 写出工具（不引第三方依赖）。
 *
 * <p>按 RFC 4180 转义：字段包含逗号、双引号、换行或首尾空白时整体加双引号，
 * 内部双引号写成两个双引号。导出文件统一带 UTF-8 BOM，Excel 直接打开中文不乱码。</p>
 */
public final class CsvWriter {

    /** UTF-8 BOM：Excel 识别 UTF-8 编码依赖它 */
    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String CRLF = "\r\n";

    private CsvWriter() {
    }

    /**
     * 转义单个字段（null 输出空串）。
     *
     * <p>除 RFC 4180 规则外，额外做 CSV 公式注入防护：文件名的、复核备注等字段来自用户输入，
     * 以 {@code = + - @} 或制表符/回车开头时前置单引号，避免用 Excel 打开导出文件时被当成公式执行。</p>
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && isFormulaLike(value.charAt(0))) {
            value = "'" + value;
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || (!value.isEmpty() && (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))));
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean isFormulaLike(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
    }

    /** 拼一行（不含换行符）；非字符串值走 String.valueOf */
    public static String row(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values[i] == null ? null : String.valueOf(values[i])));
        }
        return sb.toString();
    }

    /**
     * 拼完整 CSV（BOM + 表头 + 数据行，CRLF 换行，末尾带换行）。
     */
    public static byte[] toBytes(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(row(row)).append(CRLF);
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }
}
