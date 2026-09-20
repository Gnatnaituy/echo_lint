package com.recordaudit.dfa;

/**
 * DFA 命中的敏感词及其在原文中的区间（半开区间 [start, end)）
 */
public record DfaHit(String word, int start, int end) {
}