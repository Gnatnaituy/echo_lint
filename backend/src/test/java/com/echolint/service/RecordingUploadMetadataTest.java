package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.dto.RecordingMetadata;
import com.echolint.dto.RecordingSummary;
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
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 上传元数据：落库字段、审计摘要、无元数据时的历史兼容、RecordingSummary 透出。
 */
@ExtendWith(MockitoExtension.class)
class RecordingUploadMetadataTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecordingService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        service = new RecordingService(recordingRepository, pipelineLogRepository, aiInvocationService,
                pipelineTaskService, appProperties, objectMapper, auditService, userRepository, reviewRecordRepository,
                corpusEntryRepository, new AudioFileValidator());
        lenient().when(recordingRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    Recording recording = invocation.getArgument(0);
                    recording.setId(7L);
                    return recording;
                });
    }

    /** 合法的 mp3 文件头（ID3v2 标签 + 少量负载），用于通过文件头预检 */
    private static byte[] validMp3Bytes() {
        return new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 1, 2, 3};
    }

    private MockMultipartFile audio() {
        return new MockMultipartFile("file", "call.mp3", "audio/mpeg", validMp3Bytes());
    }

    @Test
    void uploadPersistsMetadataAndAuditsIt() throws Exception {
        RecordingMetadata metadata = RecordingMetadata.of(" 张三 ", "客服一组",
                "2026-10-20T09:30:00", "inbound", "售后");

        Recording saved = service.upload(audio(), metadata);

        assertEquals("张三", saved.getAgentName());
        assertEquals("客服一组", saved.getTeamName());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), saved.getCallTime());
        assertEquals(CallDirection.INBOUND, saved.getDirection());
        assertEquals("售后", saved.getBusinessLine());
        assertEquals(RecordingStatus.PENDING, saved.getStatus());
        assertTrue(saved.getFilePath().endsWith(".mp3"));
        assertTrue(Files.exists(tempDir.resolve(saved.getFilePath())), "音频应落盘到上传目录");
        assertNotNull(saved.getAudioSha256());
        assertTrue(saved.getAudioSha256().matches("[0-9a-f]{64}"), "应记录 64 位小写 hex SHA-256");

        verify(pipelineTaskService).enqueue(7L);
        verify(auditService).record(eq(AuditAction.RECORDING_UPLOAD), eq("RECORDING"), eq(7L),
                contains("坐席 张三"));

        RecordingSummary summary = RecordingSummary.from(saved, objectMapper, 1);
        assertEquals("张三", summary.agentName());
        assertEquals("客服一组", summary.teamName());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), summary.callTime());
        assertEquals(CallDirection.INBOUND, summary.direction());
        assertEquals("售后", summary.businessLine());
    }

    @Test
    void uploadWithoutMetadataKeepsLegacyDefaults() throws Exception {
        Recording saved = service.upload(audio(), RecordingMetadata.EMPTY);

        assertNull(saved.getAgentName());
        assertNull(saved.getTeamName());
        assertNull(saved.getCallTime());
        assertEquals(CallDirection.UNKNOWN, saved.getDirection());
        assertNull(saved.getBusinessLine());

        RecordingSummary summary = RecordingSummary.from(saved, objectMapper, 1);
        assertEquals(CallDirection.UNKNOWN, summary.direction());
        assertNull(summary.agentName());
    }

    @Test
    void invalidMetadataFailsBeforeFileIsStored() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upload(audio(), RecordingMetadata.of(null, null, null, "SIDEWAYS", null)));

        assertFalse(Files.exists(tempDir.resolve("any.mp3")));
        verifyNoInteractions(recordingRepository, pipelineTaskService);
    }
}
