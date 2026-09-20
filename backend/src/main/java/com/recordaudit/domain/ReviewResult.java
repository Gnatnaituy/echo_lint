package com.recordaudit.domain;

/**
 * 人工复检结论
 */
public enum ReviewResult {
    CONFIRMED_VIOLATION, // 确认违规
    FALSE_POSITIVE       // 判定正常（初筛误报）
}