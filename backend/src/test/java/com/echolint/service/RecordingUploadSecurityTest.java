package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.RecordingStatus;
import com.echolint.dto.RecordingMetadata;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 上传安全：流式 SHA-256 去重（409 带 id）、文件头不符 400、失败清理临时文件、空文件/超限守卫。
 */
@ExtendWith(MockitoExtension.class)
class RecordingUploadSecurityTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private AiInvocationService aiInvocationService;
    @Mock
    private PipelineService pipelineService;
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

    private AppProperties appProperties;
    private RecordingService service;

    /** 合法 mp3：ID3v2 头 + 少量负载 */
    private static final byte[] MP3_BYTES = new byte[]{
            'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4};

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());
        service = new RecordingService(recordingRepository, pipelineLogRepository, aiInvocationService,
                pipelineService, appProperties, new ObjectMapper(), auditService, userRepository,
                reviewRecordRepository, corpusEntryRepository, new AudioFileValidator());
        lenient().when(recordingRepository.saveAndFlush(any(Recording.class)))
                .thenAnswer(invocation -> {
                    Recording recording = invocation.getArgument(0);
                    recording.setId(7L);
                    return recording;
                });
    }

    private MockMultipartFile upload(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName, "audio/mpeg", content);
    }

    private long storedFileCount() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    @Test
    void uploadPersistsSha256AndLeavesNoTempFiles() throws IOException {
        Recording saved = service.upload(upload("call.mp3", MP3_BYTES), RecordingMetadata.EMPTY);

        assertNotNull(saved.getAudioSha256());
        assertTrue(saved.getAudioSha256().matches("[0-9a-f]{64}"));
        assertTrue(Files.exists(tempDir.resolve(saved.getFilePath())));
        assertEquals(1, storedFileCount(), "临时文件应被原子移动，不留 .part");
        verify(pipelineService).run(7L);
    }

    @Test
    void duplicateAudioIsRejectedWithExistingIdAndTempCleaned() throws IOException {
        Recording first = service.upload(upload("call.mp3", MP3_BYTES), RecordingMetadata.EMPTY);
        Recording existing = new Recording();
        existing.setId(42L);
        when(recordingRepository.findByAudioSha256(first.getAudioSha256())).thenReturn(Optional.of(existing));

        BizException e = assertThrows(BizException.class,
                () -> service.upload(upload("same-call-again.mp3", MP3_BYTES.clone()), RecordingMetadata.EMPTY));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("重复录音"));
        assertTrue(e.getMessage().contains("#42"), "错误信息应带已存在的录音 id");
        assertEquals(1, storedFileCount(), "重复上传应清理临时文件，不落正式文件");
        verify(recordingRepository, times(1)).saveAndFlush(any());
        verify(pipelineService, times(1)).run(anyLong());
    }

    @Test
    void fakeExtensionIsRejectedAsBadRequestAndCleaned() throws IOException {
        MockMultipartFile fake = upload("fake.mp3",
                "#!/bin/sh\necho definitely not audio\n".getBytes(StandardCharsets.UTF_8));

        BizException e = assertThrows(BizException.class,
                () -> service.upload(fake, RecordingMetadata.EMPTY));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("文件内容与扩展名不符", e.getMessage());
        assertEquals(0, storedFileCount(), "预检失败必须清理临时文件");
        verify(recordingRepository, never()).saveAndFlush(any());
        verify(pipelineService, never()).run(anyLong());
    }

    @Test
    void zeroByteFileIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.upload(upload("empty.mp3", new byte[0]), RecordingMetadata.EMPTY));

        assertTrue(e.getMessage().contains("为空"));
        verify(recordingRepository, never()).saveAndFlush(any());
    }

    @Test
    void unsupportedExtensionIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.upload(upload("script.sh", new byte[]{'#'}), RecordingMetadata.EMPTY));

        assertTrue(e.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void oversizedFileIsRejectedBeforeStreaming() {
        appProperties.setMaxSizeBytes(4);

        BizException e = assertThrows(BizException.class,
                () -> service.upload(upload("big.mp3", MP3_BYTES), RecordingMetadata.EMPTY));

        assertTrue(e.getMessage().contains("超过限制"));
        verify(recordingRepository, never()).saveAndFlush(any());
    }

    @Test
    void wavWithValidHeaderIsAccepted() {
        byte[] wav = new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E', 0, 0};

        Recording saved = service.upload(upload("call.wav", wav), RecordingMetadata.EMPTY);

        assertEquals(RecordingStatus.PENDING, saved.getStatus());
        assertTrue(saved.getFilePath().endsWith(".wav"));
        assertTrue(saved.getAudioSha256().matches("[0-9a-f]{64}"));
    }
}
