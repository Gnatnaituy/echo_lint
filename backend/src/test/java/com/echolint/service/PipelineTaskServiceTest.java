package com.echolint.service;

import com.echolint.domain.NotifyEvent;
import com.echolint.domain.TaskStatus;
import com.echolint.dto.PipelineTaskView;
import com.echolint.entity.PipelineTask;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineTaskRepository;
import com.echolint.repository.RecordingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务队列核心行为（Batch 3b-1）：入队幂等、领取去重、成功/失败状态流转、
 * 退避 30/120/300s、死信与死信通知、管理员重试、启动恢复、分页视图。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineTaskServiceTest {

    private static final Long RECORDING_ID = 5L;
    private static final Long TASK_ID = 100L;

    @Mock
    private PipelineTaskRepository taskRepository;
    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private NotificationService notificationService;

    private PipelineTaskService service;

    @BeforeEach
    void setUp() {
        service = new PipelineTaskService(taskRepository, recordingRepository, notificationService);
        when(taskRepository.save(any(PipelineTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Recording recording() {
        Recording recording = new Recording();
        recording.setId(RECORDING_ID);
        recording.setFileName("call.mp3");
        recording.setFilePath("stored.mp3");
        recording.setFileSize(100L);
        return recording;
    }

    private PipelineTask task(TaskStatus status, int attempts) {
        PipelineTask task = new PipelineTask();
        task.setId(TASK_ID);
        task.setRecordingId(RECORDING_ID);
        task.setStatus(status);
        task.setAttempts(attempts);
        task.setMaxAttempts(PipelineTask.DEFAULT_MAX_ATTEMPTS);
        task.setNextAttemptAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    private void stubEnqueueLookups(Optional<PipelineTask> active) {
        when(recordingRepository.findWithLockById(RECORDING_ID)).thenReturn(Optional.of(recording()));
        when(taskRepository.findFirstByRecordingIdAndStatusInOrderByIdDesc(eq(RECORDING_ID), any()))
                .thenReturn(active);
    }

    // ------------------------------------------------------------------ 入队幂等

    @Test
    void enqueueCreatesPendingTaskForNewRecording() {
        stubEnqueueLookups(Optional.empty());
        LocalDateTime before = LocalDateTime.now();

        PipelineTask task = service.enqueue(RECORDING_ID);

        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(0, task.getAttempts());
        assertEquals(PipelineTask.DEFAULT_MAX_ATTEMPTS, task.getMaxAttempts());
        assertNotNull(task.getNextAttemptAt());
        assertFalse(task.getNextAttemptAt().isBefore(before), "新任务应立即到期");
        verify(taskRepository).save(any(PipelineTask.class));
    }

    @Test
    void enqueueIsIdempotentForExistingPendingTask() {
        PipelineTask existing = task(TaskStatus.PENDING, 0);
        stubEnqueueLookups(Optional.of(existing));

        PipelineTask result = service.enqueue(RECORDING_ID);

        assertSame(existing, result);
        verify(taskRepository, never()).save(any(PipelineTask.class));
    }

    @Test
    void enqueueIsIdempotentForRunningTask() {
        PipelineTask existing = task(TaskStatus.RUNNING, 1);
        stubEnqueueLookups(Optional.of(existing));

        assertSame(existing, service.enqueue(RECORDING_ID));
        verify(taskRepository, never()).save(any(PipelineTask.class));
    }

    @Test
    void enqueueCreatesNewTaskAfterPreviousDead() {
        stubEnqueueLookups(Optional.empty());

        // DEAD/SUCCEEDED 不属于活动任务，重新入队会新建一条
        PipelineTask created = service.enqueue(RECORDING_ID);

        assertEquals(TaskStatus.PENDING, created.getStatus());
        verify(taskRepository).save(any(PipelineTask.class));
    }

    @Test
    void enqueueRejectsMissingRecording() {
        when(recordingRepository.findWithLockById(RECORDING_ID)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.enqueue(RECORDING_ID));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        verify(taskRepository, never()).save(any(PipelineTask.class));
    }

    @Test
    void enqueueRejectsNullRecordingId() {
        BizException e = assertThrows(BizException.class, () -> service.enqueue(null));
        assertTrue(e.getMessage().contains("不能为空"));
    }

    // ------------------------------------------------------------------ 领取 / 调度查询

    @Test
    void findDueTasksFetchesAtMostFive() {
        when(taskRepository.findDue(eq(TaskStatus.PENDING), any(LocalDateTime.class),
                argThat(pageable -> pageable.getPageSize() == PipelineTaskService.BATCH_SIZE
                        && pageable.getOffset() == 0)))
                .thenReturn(List.of(task(TaskStatus.PENDING, 0)));

        List<PipelineTask> due = service.findDueTasks();

        assertEquals(1, due.size());
        verify(taskRepository).findDue(eq(TaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void claimSucceedsOnlyWhenConditionalUpdateHits() {
        when(taskRepository.markRunning(eq(TASK_ID), any(LocalDateTime.class))).thenReturn(1);
        assertTrue(service.claim(TASK_ID));

        when(taskRepository.markRunning(eq(TASK_ID), any(LocalDateTime.class))).thenReturn(0);
        assertFalse(service.claim(TASK_ID), "已被其它实例领取时必须返回 false，避免重复执行");
    }

    // ------------------------------------------------------------------ 成功 / 失败 / 退避 / 死信

    @Test
    void markSucceededFinishesTask() {
        PipelineTask task = task(TaskStatus.RUNNING, 1);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        PipelineTask saved = service.markSucceeded(TASK_ID);

        assertEquals(TaskStatus.SUCCEEDED, saved.getStatus());
        assertNotNull(saved.getFinishedAt());
        assertNull(saved.getLastError());
        assertNull(saved.getNextAttemptAt());
    }

    @Test
    void firstFailureBacksOffThirtySeconds() {
        PipelineTask task = task(TaskStatus.RUNNING, 0);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        LocalDateTime lowerBound = LocalDateTime.now().plusSeconds(29);

        PipelineTask saved = service.markFailed(TASK_ID, "transcribe failed");

        assertEquals(TaskStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getAttempts());
        assertEquals("transcribe failed", saved.getLastError());
        assertNotNull(saved.getNextAttemptAt());
        assertTrue(saved.getNextAttemptAt().isAfter(lowerBound), "第一次失败应退避 30s");
        assertEquals(30, PipelineTaskService.backoffSeconds(1));
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void secondFailureBacksOffTwoMinutes() {
        PipelineTask task = task(TaskStatus.RUNNING, 1);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        LocalDateTime lowerBound = LocalDateTime.now().plusSeconds(119);

        PipelineTask saved = service.markFailed(TASK_ID, "still broken");

        assertEquals(TaskStatus.PENDING, saved.getStatus());
        assertEquals(2, saved.getAttempts());
        assertTrue(saved.getNextAttemptAt().isAfter(lowerBound), "第二次失败应退避 120s");
        assertEquals(120, PipelineTaskService.backoffSeconds(2));
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void backoffCapsAtFiveMinutes() {
        assertEquals(300, PipelineTaskService.backoffSeconds(3));
        assertEquals(300, PipelineTaskService.backoffSeconds(9));
    }

    @Test
    void exhaustedRetriesBecomeDeadAndNotifyOnce() {
        PipelineTask task = task(TaskStatus.RUNNING, 2);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(recordingRepository.findById(RECORDING_ID)).thenReturn(Optional.of(recording()));

        PipelineTask saved = service.markFailed(TASK_ID, "ffmpeg crashed");

        assertEquals(TaskStatus.DEAD, saved.getStatus());
        assertEquals(3, saved.getAttempts());
        assertNotNull(saved.getFinishedAt(), "死信应记录结束时间");
        verify(notificationService).notify(eq(NotifyEvent.RECORDING_FAILED), eq(RECORDING_ID),
                anyString(), contains("ffmpeg crashed"), isNull());
    }

    @Test
    void notificationFailureDoesNotBreakDeadTransition() {
        PipelineTask task = task(TaskStatus.RUNNING, 2);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(recordingRepository.findById(RECORDING_ID)).thenReturn(Optional.of(recording()));
        org.mockito.Mockito.doThrow(new RuntimeException("webhook down"))
                .when(notificationService).notify(any(), any(), anyString(), anyString(), any());

        PipelineTask saved = assertDoesNotThrow(() -> service.markFailed(TASK_ID, "boom"));

        assertEquals(TaskStatus.DEAD, saved.getStatus());
    }

    @Test
    void unrecoverableFailureBecomesFailedWithoutAutoRetryOrNotification() {
        PipelineTask task = task(TaskStatus.RUNNING, 0);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        PipelineTask saved = service.markFailedTerminal(TASK_ID, "录音不存在: 5");

        assertEquals(TaskStatus.FAILED, saved.getStatus());
        assertNotNull(saved.getFinishedAt());
        assertNull(saved.getNextAttemptAt());
        verify(notificationService, never()).notify(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void markFailedOnMissingTaskIsNoop() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
        assertNull(service.markFailed(TASK_ID, "gone"));
    }

    @Test
    void requeueRunningTask() {
        when(taskRepository.requeueRunning(eq(TASK_ID), any(LocalDateTime.class))).thenReturn(1);
        service.requeue(TASK_ID);
        verify(taskRepository).requeueRunning(eq(TASK_ID), any(LocalDateTime.class));
    }

    // ------------------------------------------------------------------ 启动恢复 / 管理员重试

    @Test
    void recoverRunningTasksDelegatesToBulkUpdate() {
        when(taskRepository.recoverRunning(any(LocalDateTime.class))).thenReturn(2);

        assertEquals(2, service.recoverRunningTasks());
        verify(taskRepository).recoverRunning(any(LocalDateTime.class));
    }

    @Test
    void retryDeadTaskResetsToPendingWithZeroAttempts() {
        PipelineTask task = task(TaskStatus.DEAD, 3);
        task.setLastError("boom");
        task.setFinishedAt(LocalDateTime.now());
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(recordingRepository.findAllById(any())).thenReturn(List.of(recording()));

        PipelineTaskView view = service.retry(TASK_ID);

        assertEquals(TaskStatus.PENDING, view.status());
        assertEquals(0, view.attempts());
        assertNull(view.lastError());
        assertNull(view.finishedAt());
        assertNotNull(view.nextAttemptAt());
        assertEquals("call.mp3", view.recordingFileName());
    }

    @Test
    void retryRejectsNonTerminalTask() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.PENDING, 1)));

        BizException e = assertThrows(BizException.class, () -> service.retry(TASK_ID));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("仅 DEAD/FAILED"));
    }

    @Test
    void retryMissingTaskIsNotFound() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.retry(TASK_ID));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    // ------------------------------------------------------------------ 查询 / 参数

    @Test
    void queryReturnsRecordingFileName() {
        PipelineTask task = task(TaskStatus.DEAD, 3);
        Page<PipelineTask> page = new PageImpl<>(List.of(task), PageRequest.of(0, 20), 1);
        when(taskRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(recordingRepository.findAllById(any())).thenReturn(List.of(recording()));

        Page<PipelineTaskView> result = service.query(TaskStatus.DEAD, RECORDING_ID, PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals("call.mp3", result.getContent().get(0).recordingFileName());
        assertEquals(TaskStatus.DEAD, result.getContent().get(0).status());
    }

    @Test
    void hasActiveTaskChecksPendingAndRunning() {
        when(taskRepository.existsByRecordingIdAndStatusIn(eq(RECORDING_ID), any())).thenReturn(true);
        assertTrue(service.hasActiveTask(RECORDING_ID));

        when(taskRepository.existsByRecordingIdAndStatusIn(eq(RECORDING_ID), eq(List.of(TaskStatus.RUNNING))))
                .thenReturn(false);
        assertFalse(service.isRunning(RECORDING_ID));
    }

    @Test
    void parseStatusIsCaseInsensitiveAndRejectsUnknownValues() {
        assertNull(PipelineTaskService.parseStatus(null));
        assertNull(PipelineTaskService.parseStatus("  "));
        assertEquals(TaskStatus.DEAD, PipelineTaskService.parseStatus(" dead "));
        assertEquals(TaskStatus.PENDING, PipelineTaskService.parseStatus("PENDING"));
        assertThrows(BizException.class, () -> PipelineTaskService.parseStatus("SIDEWAYS"));
    }
}
