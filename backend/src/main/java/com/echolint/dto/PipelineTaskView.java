package com.echolint.dto;

import com.echolint.domain.TaskStatus;
import com.echolint.entity.PipelineTask;

import java.time.LocalDateTime;

/**
 * 任务队列视图（GET /api/tasks）：附带录音文件名，便于管理员定位是哪个文件。
 */
public record PipelineTaskView(
        Long id,
        Long recordingId,
        String recordingFileName,
        TaskStatus status,
        int attempts,
        int maxAttempts,
        LocalDateTime nextAttemptAt,
        String lastError,
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
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt());
    }
}
