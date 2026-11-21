package com.echolint.entity;

import com.echolint.domain.TaskStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 异步流水线任务（Batch 3b-1）：上传/重新处理写一条任务记录，调度器按 nextAttemptAt 领取执行， 保证服务重启不丢任务、失败可退避重试、重试耗尽进入死信。
 *
 * <p>历史数据（Batch 3b-1 之前上传的录音）没有任务记录，启动恢复时按「无活动任务」处理。
 */
@Entity
@Table(
        name = "pipeline_tasks",
        indexes = {
            // 调度器固定查询：status = PENDING and nextAttemptAt <= now
            @Index(name = "idx_pipeline_task_status_next", columnList = "status,nextAttemptAt"),
            // 录音详情/幂等入队/启动恢复
            @Index(name = "idx_pipeline_task_recording", columnList = "recordingId")
        })
@Getter
@Setter
public class PipelineTask {

    /** 自动重试次数上限（默认 3 次执行：首跑 + 2 次重试；达到即死信） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recordingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.PENDING;

    /** 已失败次数（成功/入队不计数） */
    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    /** 下次可执行时间；PENDING 且为空视为立即可执行（兼容手写数据） */
    private LocalDateTime nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    /**
     * 入队时捕获的 W3C traceparent（{@code 00-traceId-spanId-flags}，来自上传/重新处理的 HTTP span）：执行时恢复为 remote
     * parent，使流水线与上传请求在 Jaeger 里连成同一条 trace。 可空（tracing 未启用 / 异步入队无 span / 历史数据）；重试保留原值，子树仍续上传
     * trace。
     */
    @Column(length = 55)
    private String traceparent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
