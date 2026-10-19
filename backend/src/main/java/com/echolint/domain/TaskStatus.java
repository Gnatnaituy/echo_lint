package com.echolint.domain;

/**
 * 异步流水线任务状态（Batch 3b-1）。
 *
 * <p>状态流转：</p>
 * <pre>
 * PENDING --领取--> RUNNING --成功--> SUCCEEDED
 *                       |--可重试失败--> PENDING（退避 30s/120s/300s 封顶）
 *                       |--重试耗尽--> DEAD（死信，发 RECORDING_FAILED 通知）
 * PENDING/RUNNING --不可执行（录音已删除等）--> FAILED
 * DEAD/FAILED --管理员重试--> PENDING（attempts=0）
 * </pre>
 */
public enum TaskStatus {
    /** 待执行（含退避重试等待中） */
    PENDING,
    /** 已领取、正在执行 */
    RUNNING,
    /** 执行成功 */
    SUCCEEDED,
    /** 终态失败：不触发自动重试（如录音已不存在） */
    FAILED,
    /** 死信：自动重试次数耗尽 */
    DEAD
}
