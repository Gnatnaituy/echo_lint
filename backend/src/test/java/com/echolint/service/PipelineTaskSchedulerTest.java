package com.echolint.service;

import com.echolint.domain.TaskStatus;
import com.echolint.entity.PipelineTask;
import com.echolint.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务调度器（Batch 3b-1）：领取后提交执行、成功/失败回写、非法/不存在任务跳过、
 * 调度异常不外抛、提交失败退回 PENDING。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineTaskSchedulerTest {

    private static final Long TASK_ID = 100L;
    private static final Long RECORDING_ID = 9L;

    @Mock
    private PipelineTaskService taskService;
    @Mock
    private PipelineService pipelineService;

    private PipelineTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PipelineTaskScheduler(taskService, pipelineService, Runnable::run);
    }

    private PipelineTask task() {
        PipelineTask task = new PipelineTask();
        task.setId(TASK_ID);
        task.setRecordingId(RECORDING_ID);
        task.setStatus(TaskStatus.PENDING);
        task.setAttempts(0);
        task.setMaxAttempts(3);
        task.setNextAttemptAt(LocalDateTime.now());
        return task;
    }

    @Test
    void dueTaskIsClaimedAndExecutedThenSucceeded() {
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        when(taskService.get(TASK_ID)).thenReturn(Optional.of(task()));

        scheduler.dispatchDueTasks();

        verify(taskService).claim(TASK_ID);
        verify(pipelineService).processNow(RECORDING_ID);
        verify(taskService).markSucceeded(TASK_ID);
    }

    @Test
    void failedExecutionRecordsFailure() {
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        when(taskService.get(TASK_ID)).thenReturn(Optional.of(task()));
        doThrow(new RuntimeException("ffmpeg crashed")).when(pipelineService).processNow(RECORDING_ID);

        scheduler.dispatchDueTasks();

        verify(taskService).markFailed(TASK_ID, "ffmpeg crashed");
        verify(taskService, never()).markSucceeded(any());
    }

    @Test
    void missingRecordingMarksTerminalFailure() {
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        when(taskService.get(TASK_ID)).thenReturn(Optional.of(task()));
        doThrow(new BizException("录音不存在: 9", HttpStatus.NOT_FOUND))
                .when(pipelineService).processNow(RECORDING_ID);

        scheduler.dispatchDueTasks();

        verify(taskService).markFailedTerminal(eq(TASK_ID), contains("录音不存在"));
        verify(taskService, never()).markFailed(any(), anyString());
    }

    @Test
    void taskClaimedByAnotherInstanceIsNotExecuted() {
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(false);

        scheduler.dispatchDueTasks();

        verify(pipelineService, never()).processNow(any());
        verify(taskService, never()).markSucceeded(any());
    }

    @Test
    void missingTaskIsSkipped() {
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(true);
        when(taskService.get(TASK_ID)).thenReturn(Optional.empty());

        scheduler.dispatchDueTasks();

        verify(pipelineService, never()).processNow(any());
    }

    @Test
    void schedulingQueryFailureDoesNotThrow() {
        when(taskService.findDueTasks()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> scheduler.dispatchDueTasks());
    }

    @Test
    void executorRejectionRequeuesTask() {
        TaskExecutor rejecting = command -> {
            throw new RuntimeException("pool shutdown");
        };
        scheduler = new PipelineTaskScheduler(taskService, pipelineService, rejecting);
        when(taskService.findDueTasks()).thenReturn(List.of(task()));
        when(taskService.claim(TASK_ID)).thenReturn(true);

        scheduler.dispatchDueTasks();

        verify(taskService).requeue(TASK_ID);
        verify(pipelineService, never()).processNow(any());
    }
}
