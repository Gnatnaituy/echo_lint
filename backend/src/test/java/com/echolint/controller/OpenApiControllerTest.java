package com.echolint.controller;

import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
import com.echolint.dto.OpenRecordingView;
import com.echolint.dto.OpenUploadResponse;
import com.echolint.dto.RecordingMetadata;
import com.echolint.entity.Recording;
import com.echolint.service.ApiKeyService;
import com.echolint.service.RecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 开放 API（Batch 4a）：上传返回 202 最小响应并透传元数据/密钥名称；查询只回限定状态字段。
 */
@ExtendWith(MockitoExtension.class)
class OpenApiControllerTest {

    @Mock
    private RecordingService recordingService;

    private OpenApiController controller;

    @BeforeEach
    void setUp() {
        controller = new OpenApiController(recordingService);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "call.mp3", "audio/mpeg", new byte[]{'I', 'D', '3', 1, 2, 3});
    }

    private ApiKeyService.ApiKeyContext context(String name) {
        return new ApiKeyService.ApiKeyContext(7L, name, "elk_abcd...1234");
    }

    @Test
    void uploadReturns202MinimalResponseAndPassesMetadata() {
        Recording saved = new Recording();
        saved.setId(42L);
        saved.setFileName("call.mp3");
        saved.setStatus(RecordingStatus.PENDING);
        when(recordingService.uploadFromOpenApi(any(), any(RecordingMetadata.class), eq("ci-runner")))
                .thenReturn(saved);

        ResponseEntity<OpenUploadResponse> response = controller.upload(context("ci-runner"), file(),
                " 张三 ", "客服一组", "2026-10-20T09:30:00", "inbound", "售后");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(42L, response.getBody().id());
        assertEquals("PENDING", response.getBody().status());
        assertEquals("call.mp3", response.getBody().fileName());

        ArgumentCaptor<RecordingMetadata> captor = ArgumentCaptor.forClass(RecordingMetadata.class);
        org.mockito.Mockito.verify(recordingService)
                .uploadFromOpenApi(any(), captor.capture(), eq("ci-runner"));
        RecordingMetadata metadata = captor.getValue();
        assertEquals("张三", metadata.agentName());
        assertEquals("客服一组", metadata.teamName());
        assertEquals("售后", metadata.businessLine());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), metadata.callTime());
        assertEquals("INBOUND", metadata.direction().name());
    }

    @Test
    void uploadWithoutKeyContextUsesUnknownActor() {
        Recording saved = new Recording();
        saved.setId(1L);
        saved.setFileName("a.mp3");
        saved.setStatus(RecordingStatus.PENDING);
        when(recordingService.uploadFromOpenApi(any(), any(), eq("unknown"))).thenReturn(saved);

        controller.upload(null, file(), null, null, null, null, null);

        org.mockito.Mockito.verify(recordingService).uploadFromOpenApi(any(), any(), eq("unknown"));
    }

    @Test
    void uploadRejectsInvalidDirectionBeforeService() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.upload(context("ci-runner"), file(),
                        null, null, null, "SIDEWAYS", null));
        org.mockito.Mockito.verifyNoInteractions(recordingService);
    }

    @Test
    void getReturnsLimitedViewWithoutInternalContent() {
        Recording recording = new Recording();
        recording.setId(5L);
        recording.setStatus(RecordingStatus.NEEDS_REVIEW);
        recording.setFileName("call.mp3");
        recording.setUploadTime(LocalDateTime.of(2026, 10, 20, 9, 0));
        recording.setProcessedTime(LocalDateTime.of(2026, 10, 20, 9, 5));
        recording.setDurationSeconds(42);
        recording.setHitCount(3);
        recording.setViolationType("INSULT");
        recording.setReviewResult(ReviewResult.CONFIRMED_VIOLATION);
        recording.setErrorMessage("some error");
        recording.setTranscript("full transcript should never leave this entity");
        when(recordingService.get(5L)).thenReturn(recording);

        OpenRecordingView view = controller.get(5L);

        assertEquals(5L, view.id());
        assertEquals("NEEDS_REVIEW", view.status());
        assertEquals("call.mp3", view.fileName());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 0), view.uploadTime());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 5), view.processedTime());
        assertEquals(42, view.durationSeconds());
        assertEquals(3, view.hitCount());
        assertEquals("INSULT", view.violationType());
        assertEquals("CONFIRMED_VIOLATION", view.reviewResult());
        assertEquals("some error", view.errorMessage());
        // 视图 record 不含 transcript/aiResultJson/reviewComment 等内部字段（编译期保证）
        assertFalse(java.util.Arrays.stream(OpenRecordingView.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("transcript")));
    }
}
