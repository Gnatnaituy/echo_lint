package com.echolint.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV 写出/解析：转义（逗号/双引号/换行/首尾空白）、BOM、引号内换行、空字段。
 */
class CsvUtilsTest {

    @Test
    void escapeWrapsCommasQuotesNewlinesAndPadding() {
        assertEquals("plain", CsvWriter.escape("plain"));
        assertEquals("", CsvWriter.escape(null));
        assertEquals("", CsvWriter.escape(""));
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"));
        assertEquals("\"he said \"\"hi\"\"\"", CsvWriter.escape("he said \"hi\""));
        assertEquals("\"line1\nline2\"", CsvWriter.escape("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", CsvWriter.escape("line1\r\nline2"));
        assertEquals("\" padded \"", CsvWriter.escape(" padded "));
        assertEquals("中文正常输出", CsvWriter.escape("中文正常输出"));
    }

    @Test
    void escapeNeutralizesCsvFormulaInjection() {
        assertEquals("'=cmd|' /C calc'!A0", CsvWriter.escape("=cmd|' /C calc'!A0"));
        assertEquals("'+123", CsvWriter.escape("+123"));
        assertEquals("'-2", CsvWriter.escape("-2"));
        assertEquals("'@SUM(A1:A2)", CsvWriter.escape("@SUM(A1:A2)"));
        // 前置单引号后仍按 RFC 4180 处理逗号
        assertEquals("\"'=cmd,with,commas\"", CsvWriter.escape("=cmd,with,commas"));
    }

    @Test
    void rowJoinsEscapedFieldsAndWritesEmptyForNull() {
        assertEquals("1,\"a,b\",", CsvWriter.row(1, "a,b", null));
        assertEquals("plain,true,0.91", CsvWriter.row("plain", true, 0.91));
    }

    @Test
    void toBytesPrependsUtf8BomAndUsesCrlf() {
        byte[] bytes = CsvWriter.toBytes(List.<String[]>of(new String[]{"word", "enabled"}));

        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                Arrays.copyOf(bytes, CsvWriter.UTF8_BOM.length));
        assertEquals("word,enabled\r\n", new String(bytes, CsvWriter.UTF8_BOM.length,
                bytes.length - CsvWriter.UTF8_BOM.length, StandardCharsets.UTF_8));
    }

    @Test
    void parserHandlesBomQuotedFieldsAndEmbeddedNewlines() {
        String content = "\uFEFFword,category,enabled\r\n"
                + "\"foo,bar\",\"he said \"\"hi\"\"\",true\r\n"
                + "\"multi\nline\",cat,false\r\n";

        List<List<String>> rows = CsvParser.parse(content);

        assertEquals(3, rows.size());
        assertEquals(List.of("word", "category", "enabled"), rows.get(0));
        assertEquals(List.of("foo,bar", "he said \"hi\"", "true"), rows.get(1));
        assertEquals(List.of("multi\nline", "cat", "false"), rows.get(2));
    }

    @Test
    void parserHandlesTrailingEmptyFieldAndBlankInput() {
        assertEquals(List.of(List.of("a", "")), CsvParser.parse("a,"));
        assertEquals(List.of(List.of("a", "b")), CsvParser.parse("a,b\n"));
        assertEquals(List.of(List.of("", "")), CsvParser.parse(",\n"));
        assertTrue(CsvParser.parse("").isEmpty());
        assertTrue(CsvParser.parse(null).isEmpty());
    }

    @Test
    void writerOutputRoundTripsThroughParser() {
        String tricky = "line1\nline2, \"quoted\"";
        byte[] bytes = CsvWriter.toBytes(List.<String[]>of(new String[]{"name", tricky}, new String[]{"中文", ""}));
        String text = new String(bytes, CsvWriter.UTF8_BOM.length,
                bytes.length - CsvWriter.UTF8_BOM.length, StandardCharsets.UTF_8);

        List<List<String>> rows = CsvParser.parse(text);

        assertEquals(2, rows.size());
        assertEquals(tricky, rows.get(0).get(1));
        assertEquals("中文", rows.get(1).get(0));
        assertEquals("", rows.get(1).get(1));
    }
}
