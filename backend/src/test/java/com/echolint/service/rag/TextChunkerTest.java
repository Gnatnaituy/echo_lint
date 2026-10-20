package com.echolint.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本分块：边界优先、重叠、空文本与短文本。
 */
class TextChunkerTest {

    @Test
    void blankTextReturnsEmptyList() {
        assertTrue(TextChunker.split(null, 100, 10).isEmpty());
        assertTrue(TextChunker.split("", 100, 10).isEmpty());
        assertTrue(TextChunker.split("   \n\t ", 100, 10).isEmpty());
    }

    @Test
    void shortTextIsSingleChunkStripped() {
        List<String> chunks = TextChunker.split("  你好，世界。  ", 100, 10);
        assertEquals(List.of("你好，世界。"), chunks);
    }

    @Test
    void splitsAtSentenceBoundary() {
        String text = "第一句话内容比较长，需要切分。" + "第二句话内容也比较长，需要切分。"
                + "第三句话。";
        List<String> chunks = TextChunker.split(text, 20, 4);
        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 20, "每块不应超过上限: " + chunk.length());
           assertFalse(chunk.isBlank());
        }
        assertTrue(chunks.get(0).endsWith("。"), "应优先在句末标点切分");
        assertTrue(chunks.get(chunks.size() - 1).endsWith("。"), "最后一块应覆盖原文结尾");
    }

    @Test
    void overlapsAdjacentChunks() {
        String text = "一二三四五六七八九十".repeat(5);
        List<String> chunks = TextChunker.split(text, 20, 5);
        assertTrue(chunks.size() >= 2);
        String tail = chunks.get(0).substring(chunks.get(0).length() - 5);
        assertTrue(chunks.get(1).startsWith(tail), "下一块应包含上一块末尾的重叠内容");
    }

    @Test
    void clampsOverlapToHalfChunk() {
        List<String> chunks = TextChunker.split("abcdefghijklmnopqrstuvwxyz", 10, 99);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(1).length() <= 10);
    }

    @Test
    void handlesNewlinesAsBoundary() {
        String text = "line one content\nline two content\nline three content";
        List<String> chunks = TextChunker.split(text, 18, 0);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).startsWith("line one"));
    }
}
