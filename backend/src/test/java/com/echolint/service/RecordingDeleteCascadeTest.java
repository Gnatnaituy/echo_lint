package com.echolint.service;

import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.Recording;
import com.echolint.repository.AppUserRepository;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

/**
 * 删除录音：派生数据（流水线日志、AI 留痕、复核记录、语料）全部级联清理，音频文件删除。
 */
@ExtendWith(MockitoExtension.class)
class RecordingDeleteCascadeTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private AiInvocationService aiInvocationService;
    @Mock
    private PipelineTaskService pipelineTaskService;
    @Mock
    private AuditService auditService;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;
    @Mock
    private CorpusEntryRepository corpusEntryRepository;

    @TempDir
    Path tempDir;

    private RecordingService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        service = new RecordingService(recordingRepository, pipelineLogRepository, aiInvocationService,
                pipelineTaskService, appProperties, new ObjectMapper(), auditService, userRepository,
                reviewRecordRepository, corpusEntryRepository, new AudioFileValidator());
    }

    @Test
    void deleteCascadesCorpusAndOtherDerivedData() throws IOException {
        Recording recording = new Recording();
        recording.setId(11L);
        recording.setFileName("call.mp3");
        recording.setFilePath("stored.mp3");
        recording.setFileSize(10L);
        recording.setStatus(RecordingStatus.VIOLATION_CONFIRMED);
        Path audio = tempDir.resolve("stored.mp3");
        Files.write(audio, new byte[]{'I', 'D', '3'});

        when(recordingRepository.findById(11L)).thenReturn(Optional.of(recording));
        when(pipelineLogRepository.findByRecordingIdOrderByCreatedAtAscIdAsc(11L)).thenReturn(List.of());

        service.delete(11L);

        verify(corpusEntryRepository).deleteByRecordingId(11L);
        verify(reviewRecordRepository).deleteByRecordingId(11L);
        verify(aiInvocationService).deleteByRecording(11L);
        verify(recordingRepository).delete(recording);
        assertFalse(Files.exists(audio), "音频文件应被删除");
    }

    @Test
    void deleteIsRejectedWhilePipelineIsRunning() {
        Recording recording = new Recording();
        recording.setId(12L);
        recording.setFileName("call.mp3");
        recording.setStatus(RecordingStatus.AI_CHECKING);
        when(recordingRepository.findById(12L)).thenReturn(Optional.of(recording));
        when(pipelineTaskService.isRunning(12L)).thenReturn(true);

        BizException e = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> service.delete(12L));

        org.junit.jupiter.api.Assertions.assertEquals(409, e.getStatus().value());
        verify(recordingRepository, never()).delete(org.mockito.ArgumentMatchers.<com.echolint.entity.Recording>any());
    }
}
