package com.echolint.service;

import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复（Batch 3b-1）：先重置 RUNNING 任务，再只对「无活动任务」的中断录音强制 FAILED；
 * 有 PENDING/RUNNING 任务的录音交给任务队列续跑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineRecoveryServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private PipelineTaskService pipelineTaskService;

    private PipelineRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRecoveryService(recordingRepository, pipelineLogRepository, pipelineTaskService);
    }

    private Recording recording(Long id, RecordingStatus status) {
        Recording recording = new Recording();
        recording.setId(id);
        recording.setFileName("call-" + id + ".mp3");
        recording.setFilePath("stored-" + id + ".mp3");
        recording.setFileSize(100L);
        recording.setStatus(status);
        return recording;
    }

    @Test
    void runningTasksAreRecoveredBeforeRecordingChecks() {
        when(recordingRepository.findByStatusIn(any())).thenReturn(List.of());

        service.onApplicationEvent(null);

        InOrder order = inOrder(pipelineTaskService, recordingRepository);
        order.verify(pipelineTaskService).recoverRunningTasks();
        order.verify(recordingRepository).findByStatusIn(any());
    }

    @Test
    void interruptedRecordingWithActiveTaskIsNotForceFailed() {
        Recording recording = recording(1L, RecordingStatus.TRANSCRIBING);
        when(recordingRepository.findByStatusIn(any())).thenReturn(List.of(recording));
        when(pipelineTaskService.hasActiveTask(1L)).thenReturn(true);

        service.onApplicationEvent(null);

        assertEquals(RecordingStatus.TRANSCRIBING, recording.getStatus(), "有活动任务时交给任务队列续跑");
        verify(recordingRepository, never()).save(any(Recording.class));
        verify(pipelineLogRepository).save(argThat(log ->
                log.getRecordingId().equals(1L) && "INFO".equals(log.getLevel())));
    }

    @Test
    void interruptedRecordingWithoutTaskIsForceFailedForManualRetry() {
        Recording recording = recording(2L, RecordingStatus.AI_CHECKING);
        when(recordingRepository.findByStatusIn(any())).thenReturn(List.of(recording));
        when(pipelineTaskService.hasActiveTask(2L)).thenReturn(false);

        service.onApplicationEvent(null);

        assertEquals(RecordingStatus.FAILED, recording.getStatus());
        assertTrue(recording.getErrorMessage().contains("服务重启"));
        verify(recordingRepository).save(recording);
        verify(pipelineLogRepository).save(argThat(log ->
                log.getRecordingId().equals(2L) && "WARN".equals(log.getLevel())));
    }

    @Test
    void mixedInterruptedRecordingsOnlyFailThoseWithoutTasks() {
        Recording withTask = recording(3L, RecordingStatus.TRANSCRIBING);
        Recording withoutTask = recording(4L, RecordingStatus.DFA_CHECKING);
        when(recordingRepository.findByStatusIn(any())).thenReturn(List.of(withTask, withoutTask));
        when(pipelineTaskService.hasActiveTask(3L)).thenReturn(true);
        when(pipelineTaskService.hasActiveTask(4L)).thenReturn(false);

        service.onApplicationEvent(null);

        assertEquals(RecordingStatus.TRANSCRIBING, withTask.getStatus());
        assertEquals(RecordingStatus.FAILED, withoutTask.getStatus());
        verify(recordingRepository, never()).save(argThat(recording -> recording.getId().equals(3L)));
        verify(recordingRepository).save(argThat(recording -> recording.getId().equals(4L)));
        verify(pipelineTaskService).hasActiveTask(eq(3L));
        verify(pipelineTaskService).hasActiveTask(eq(4L));
    }

    @Test
    void noInterruptedRecordingsIsNoopForRecordingState() {
        when(recordingRepository.findByStatusIn(any())).thenReturn(List.of());

        service.onApplicationEvent(null);

        verify(recordingRepository, never()).save(any(Recording.class));
        verify(pipelineLogRepository, never()).save(any(PipelineLog.class));
    }
}
