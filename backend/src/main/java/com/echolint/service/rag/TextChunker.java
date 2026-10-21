package com.echolint.service.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器（RAG Phase B）。
 *
 * <p>按字符数切块，优先落在段落/句末标点/换行/空格上，避免从句中硬切。
 * 纯函数、无状态，便于单测；知识库正文与语料截断文本共用。</p>
 */
public final class TextChunker {

    /** 句末标点（中英文） */
    private static final String SENTENCE_ENDS = "。！？!?；;…\n";

    private TextChunker() {
    }

    /**
     * 切块。
     *
     * @param text       原始文本（null/空返回空列表）
     * @param chunkChars 单块最大字符数（>=1）
     * @param overlap    相邻块重叠字符数（0 ~ chunkChars/2）
     * @return 分块列表（每块去除首尾空白；空块丢弃）
     */
    public static List<String> split(String text, int chunkChars, int overlap) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return out;
        }
        int size = Math.max(1, chunkChars);
        int overlapChars = Math.min(Math.max(0, overlap), size / 2);
        String value = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int length = value.length();
        while (start < length) {
            int end = Math.min(length, start + size);
            if (end < length) {
                end = adjustBoundary(value, start, end, size);
            }
            String chunk = value.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                out.add(chunk);
            }
            if (end >= length) {
                break;
            }
            int next = end - overlapChars;
            if (next <= start) {
                next = end;
            }
            start = next;
        }
        return out;
    }

    /** 在 [start+size/2, end] 范围内回退到最近的段落/句末/换行/空格 */
    private static int adjustBoundary(String text, int start, int end, int size) {
        int floor = start + Math.max(1, size / 2);
        int best = -1;
        for (int i = end - 1; i > floor; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                return i + 1;
            }
            if (Character.isWhitespace(c)) {
                best = i + 1;
            }
        }
        return best > 0 ? best : end;
    }
}
