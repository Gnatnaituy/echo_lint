package com.echolint.domain;

/**
 * 录音处理状态机：
 * PENDING -> TRANSCRIBING -> DFA_CHECKING -> (命中) AI_CHECKING -> NEEDS_REVIEW
 *                                         -> (未命中) COMPLIANT
 * NEEDS_REVIEW -> VIOLATION_CONFIRMED | FALSE_POSITIVE
 * 任一步异常 -> FAILED
 */
public enum RecordingStatus {
    PENDING,            // 已上传，排队处理
    TRANSCRIBING,       // Whisper 转写中
    DFA_CHECKING,       // DFA 初筛中
    AI_CHECKING,        // AI 语义复筛中
    NEEDS_REVIEW,       // 初筛+复筛均判违规，待人工复检
    COMPLIANT,          // 自动通过（初筛未命中 或 复筛判定正常）
    VIOLATION_CONFIRMED,// 人工复检确认违规
    FALSE_POSITIVE,     // 人工复检判定为误报
    FAILED              // 处理失败
}