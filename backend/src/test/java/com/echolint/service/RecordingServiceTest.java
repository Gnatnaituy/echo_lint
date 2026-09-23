package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 手动推进/重新处理的状态守卫（哪些状态可重跑、在途保护、文件缺失保护）
 */
@ExtendWith(MockitoExtension.class)
class RecordingServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private PipelineService pipelineService;
    @Mock
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    private AppProperties appProperties;
    private RecordingService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        service = new RecordingService(recordingRepository, pipelineLogRepository, pipelineService, appProperties, objectMapper);
    }

    private Recording recording(RecordingStatus status) {
        Recording r = new Recording();
        r.setId(1L);
        r.setFileName("call.mp3");
        r.setFilePath("stored.mp3");
        r.setFileSize(1000L);
        r.setStatus(status);
        r.setHitCount(3);
        r.setErrorMessage("上次失败原因");
        return r;
    }

    private void stubExisting(Long id, Recording r) throws IOException {
        Files.writeString(tempDir.resolve("stored.mp3"), "audio");
        when(recordingRepository.findById(id)).thenReturn(Optional.of(r));
    }

    @Test
    void stuckPendingCanBePushedManually() throws IOException {
        Recording r = recording(RecordingStatus.PENDING);
        stubExisting(1L, r);

        Recording result = service.retry(1L);

        assertEquals(RecordingStatus.PENDING, result.getStatus());
        assertNull(result.getErrorMessage());
        assertEquals(0, result.getHitCount());
        assertNull(result.getDfaHitsJson());
        assertNull(result.getAiResultJson());
        verify(recordingRepository).saveAndFlush(r);
        verify(pipelineService).run(1L);
    }

    @Test
    void failedAndInterruptedStatesCanBeReprocessed() throws IOException {
        for (RecordingStatus status : new RecordingStatus[]{
                RecordingStatus.FAILED, RecordingStatus.TRANSCRIBING,
                RecordingStatus.DFA_CHECKING, RecordingStatus.AI_CHECKING}) {
            reset(recordingRepository, pipelineService);
            Recording r = recording(status);
            stubExisting(1L, r);

            Recording result = service.retry(1L);

            assertEquals(RecordingStatus.PENDING, result.getStatus(), status + " 应可重新处理");
            verify(pipelineService).run(1L);
        }
    }

    @Test
    void recordsWithVerdictCannotBeReprocessed() throws IOException {
        for (RecordingStatus status : new RecordingStatus[]{
                RecordingStatus.COMPLIANT, RecordingStatus.NEEDS_REVIEW,
                RecordingStatus.VIOLATION_CONFIRMED, RecordingStatus.FALSE_POSITIVE}) {
            reset(recordingRepository, pipelineService);
            Recording r = recording(status);
            stubExisting(1L, r);

            BizException e = assertThrows(BizException.class, () -> service.retry(1L));
            assertTrue(e.getMessage().contains("不支持重新处理"), "状态 " + status);
            verify(pipelineService, never()).run(anyLong());
        }
    }

    @Test
    void inFlightRecordingIsRejected() throws IOException {
        Recording r = recording(RecordingStatus.PENDING);
        stubExisting(1L, r);
        when(pipelineService.isInFlight(1L)).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> service.retry(1L));

        assertTrue(e.getMessage().contains("正在处理中"));
        verify(pipelineService, never()).run(anyLong());
        verify(recordingRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingAudioFileIsRejected() {
        Recording r = recording(RecordingStatus.PENDING);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(r));

        BizException e = assertThrows(BizException.class, () -> service.retry(1L));

        assertTrue(e.getMessage().contains("文件不存在"));
        verify(pipelineService, never()).run(anyLong());
    }
}
