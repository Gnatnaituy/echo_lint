package com.echolint.dto;

import com.echolint.domain.TaskStatus;
import com.echolint.entity.PipelineTask;
import com.echolint.util.TraceContinuation;
import java.time.LocalDateTime;

/** 任务队列视图（GET /api/tasks）：附带录音文件名，便于管理员定位是哪个文件。 */
public record PipelineTaskView(
        Long id,
        Long recordingId,
        String recordingFileName,
        TaskStatus status,
        int attempts,
        int maxAttempts,
        LocalDateTime nextAttemptAt,
        String lastError,
        /** 落库 traceparent 解出的 traceId：平台「链路追踪」页据此深链 Jaeger；历史数据为 null */
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt) {

    public static PipelineTaskView from(PipelineTask task, String recordingFileName) {
        return new PipelineTaskView(
                task.getId(),
                task.getRecordingId(),
                recordingFileName,
                task.getStatus(),
                task.getAttempts(),
                task.getMaxAttempts(),
                task.getNextAttemptAt(),
                task.getLastError(),
                TraceContinuation.traceIdOf(task.getTraceparent()),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt());
    }
}
