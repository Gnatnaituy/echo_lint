package com.echolint.service;

import com.echolint.entity.AiInvocation;
import com.echolint.repository.AiInvocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AI 调用留痕：成功/失败都要落库，且留痕本身绝不能影响稽核主流程
 */
@ExtendWith(MockitoExtension.class)
class AiInvocationServiceTest {

    @Mock
    private AiInvocationRepository repository;

    private AiInvocationService service;

    @BeforeEach
    void setUp() {
        service = new AiInvocationService(repository);
    }

    private ScreenModelService.ActiveModel model() {
        return new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "/chat/completions", true, true, 2048, Map.of(), 300, null);
    }

    private AiInvocation captured() {
        ArgumentCaptor<AiInvocation> captor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsSuccessfulExchange() {
        String request = "{\"model\":\"deepseek-flash\"}";
        String response = "{\"violation\":true}";
        service.recordSuccess(7L, AiInvocationService.STAGE_SCREEN, model(), request, response, 1234L);

        AiInvocation inv = captured();
        assertEquals(7L, inv.getRecordingId());
        assertEquals("SCREEN", inv.getStage());
        assertEquals("deepseek", inv.getProviderId());
        assertEquals("deepseek-flash", inv.getModel());
        assertEquals("https://api.deepseek.com/chat/completions", inv.getEndpoint());
        assertEquals(request, inv.getRequestJson());
        assertEquals(response, inv.getResponseJson());
        assertTrue(inv.getSuccess());
        assertFalse(inv.getDegraded());
        assertNull(inv.getErrorMessage());
        assertEquals(1234, inv.getLatencyMs());
        assertEquals(request.length(), inv.getRequestChars());
        assertEquals(response.length(), inv.getResponseChars());
    }

    @Test
    void recordsFailureWithRequestKeptForDiagnosis() {
        service.recordFailure(7L, AiInvocationService.STAGE_MINING, model(),
                "{\"messages\":[]}", "未配置 API Key", 5L);

        AiInvocation inv = captured();
        assertEquals("MINING", inv.getStage());
        assertFalse(inv.getSuccess());
        assertTrue(inv.getDegraded());
        assertEquals("未配置 API Key", inv.getErrorMessage());
        // 关键：失败时请求体仍要留下，否则无法回答「本该发出去什么」
        assertEquals("{\"messages\":[]}", inv.getRequestJson());
        assertNull(inv.getResponseJson());
        assertEquals(0, inv.getResponseChars());
    }

    @Test
    void recordingNeverBreaksThePipeline() {
        when(repository.save(any(AiInvocation.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordSuccess(1L, AiInvocationService.STAGE_SCREEN, model(),
                "{}", "{}", 1L));
    }

    @Test
    void nullModelIsTolerated() {
        service.recordFailure(1L, AiInvocationService.STAGE_SCREEN, null, null, "no provider", 0L);

        AiInvocation inv = captured();
        assertNull(inv.getModel());
        assertNull(inv.getEndpoint());
        assertEquals(0, inv.getRequestChars());
    }

    @Test
    void listsInInsertionOrderForRecording() {
        AiInvocation a = new AiInvocation();
        when(repository.findByRecordingIdOrderByIdAsc(9L)).thenReturn(List.of(a));

        assertEquals(List.of(a), service.listByRecording(9L));
    }
}
