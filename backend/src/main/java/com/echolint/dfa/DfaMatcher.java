package com.echolint.dfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DFA 敏感词匹配器（Aho-Corasick 自动机 + 字符跳变支持）。
 *
 * 特性：
 * 1. 大小写不敏感；
 * 2. 词与文本均按 {@link WordNormalizer} 规范化，命中区间仍映射回原文（半开区间）；
 * 3. 支持字符跳变：词语字母之间可插入空格/标点/emoji（如 "f u c k"、"f**k" 命中 "fuck"），
 *    但匹配必须从字母开始、以字母结束；
 * 4. 子串语义：embedded 命中（如 "unfuckingbelievable" 命中 "fuck"），
 *    由 AI 语义复筛兜底过滤误报。
 */
public final class DfaMatcher {

    private static final class Node {
        final Map<Character, Node> children = new LinkedHashMap<>();
        Node fail;
        final List<String> outputs = new ArrayList<>();
    }

    private final Node root = new Node();
    private final int totalWordCount;

    public DfaMatcher(Collection<String> words) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String w : words) {
            String n = WordNormalizer.normalize(w);
            if (!n.isEmpty()) {
                normalized.add(n);
            }
        }
        this.totalWordCount = normalized.size();
        for (String n : normalized) {
            addWord(n);
        }
        buildFailureLinks();
    }

    private void addWord(String word) {
        Node cur = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            cur = cur.children.computeIfAbsent(c, k -> new Node());
        }
        cur.outputs.add(word);
    }

    private void buildFailureLinks() {
        Deque<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            for (Map.Entry<Character, Node> e : cur.children.entrySet()) {
                char c = e.getKey();
                Node child = e.getValue();
                Node f = cur.fail;
                while (f != root && !f.children.containsKey(c)) {
                    f = f.fail;
                }
                Node next = f.children.get(c);
                child.fail = (next != null && next != child) ? next : root;
                if (!child.fail.outputs.isEmpty()) {
                    child.outputs.addAll(child.fail.outputs);
                }
                queue.add(child);
            }
        }
    }

    public List<DfaHit> match(String text) {
        List<DfaHit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Node state = root;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                // 分隔符：保持当前状态，候选可跨分隔符续接（字符跳变）
                continue;
            }
            while (state != root && !state.children.containsKey(c)) {
                state = state.fail;
            }
            Node next = state.children.get(c);
            if (next == null) {
                state = root; // 无候选
                continue;
            }
            state = next;
            if (!state.outputs.isEmpty()) {
                for (String word : state.outputs) {
                    int start = findStart(lower, i, word);
                    if (start >= 0) {
                        hits.add(new DfaHit(word, start, i + 1));
                    }
                }
            }
        }
        // 去重（同一词同一区间只保留一条）
        return new ArrayList<>(new LinkedHashSet<>(hits));
    }

    /**
     * 从文本位置 end 向前回溯定位 word 的实际起始位置（允许中间有分隔符）。
     *
     * @return 命中起始下标；无法对齐时返回 -1
     */
    private int findStart(String lower, int end, String word) {
        int k = word.length() - 1;
        int j = end;
        while (k >= 0) {
            while (j >= 0 && !Character.isLetterOrDigit(lower.charAt(j))) {
                j--;
            }
            if (j < 0 || lower.charAt(j) != word.charAt(k)) {
                return -1;
            }
            k--;
            j--;
        }
        return j + 1;
    }

    /** 规范化后的词典词数量 */
    public int wordCount() {
        return totalWordCount;
    }
}