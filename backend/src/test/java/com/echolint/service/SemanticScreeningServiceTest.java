package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AI 全文分块与严格解析（Batch 2b-2）：
 * 分块合并（任一块违规=整体违规、取最高置信度）、缺字段/非 JSON=失败降级、
 * 低置信度违规=degraded、正常合规可自动放行、超出 max-chunks 截断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemanticScreeningServiceTest {

    private static final String COMPLIANT = "{\"violation\":false,\"violation_type\":\"NONE\","
            + "\"reason\":\"no violation\",\"confidence\":0.9,\"target_sentence\":\"\"}";
    private static final String VIOLATION = "{\"violation\":true,\"violation_type\":\"INSULT\","
            + "\"reason\":\"direct insult\",\"confidence\":0.92,\"target_sentence\":\"you idiot\"}";
    private static final String LOW_CONFIDENCE_VIOLATION = "{\"violation\":true,\"violation_type\":\"THREAT\","
            + "\"reason\":\"maybe threat\",\"confidence\":0.35,\"target_sentence\":\"\"}";

    @Mock
    private ScreenModelService screenModelService;
    @Mock
    private AiInvocationService invocations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<JsonNode> modelReplies = new ArrayDeque<>();

    private ScreenModelProperties properties;
    private SemanticScreeningService service;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestBodyUriSpec uriSpec;
    private WebClient.RequestBodySpec bodySpec;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec headersSpec;
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        properties = new ScreenModelProperties();
        properties.setFewShotCount(0);

        WebClient client = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        when(client.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(JsonNode.class)).thenAnswer(invocation -> {
            JsonNode reply = modelReplies.poll();
            if (reply == null) {
                return Mono.error(new IllegalStateException("测试没有预置足够的模型回复"));
            }
            return Mono.just(reply);
        });

        ScreenModelService.ActiveModel model = new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "/chat/completions", true, true, null, Map.of(), 30, client);
        when(screenModelService.active()).thenReturn(model);

        service = new SemanticScreeningService(properties, screenModelService, invocations, objectMapper);
    }

    /** 构造 OpenAI 兼容响应，content 即模型输出的 JSON 文本 */
    private JsonNode reply(String content) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        return root;
    }

    private void queueReplies(String... contents) {
        for (String content : contents) {
            modelReplies.add(reply(content));
        }
    }

    private AiScreenResult screen(String transcript) {
        return service.screen(1L, transcript, "[]", List.of(), List.of());
    }

    @Test
    void compliantResponseIsAutoCompliant() {
        queueReplies(COMPLIANT);

        AiScreenResult result = screen("have a nice day");

        assertFalse(result.violation());
        assertFalse(result.degraded());
        assertNull(result.degradedReason());
        assertEquals(0.9, result.confidence(), 1e-9);
        assertEquals("deepseek", result.providerId());
        assertEquals("deepseek-flash", result.model());
        verify(invocations).recordSuccess(eq(1L), eq(AiInvocationService.STAGE_SCREEN), any(),
                anyString(), anyString(), anyLong());
    }

    @Test
    void violationResponseIsPassedThrough() {
        queueReplies(VIOLATION);

        AiScreenResult result = screen("you idiot");

        assertTrue(result.violation());
        assertFalse(result.degraded());
        assertEquals("INSULT", result.typeCode());
        assertEquals("you idiot", result.targetSentence());
        assertEquals(0.92, result.confidence(), 1e-9);
    }

    @Test
    void missingViolationFieldIsTreatedAsFailure() {
        queueReplies("{\"violation_type\":\"INSULT\",\"confidence\":0.99,\"reason\":\"no boolean\"}");

        BizException error = assertThrows(BizException.class, () -> screen("whatever"));

        assertTrue(error.getMessage().contains("violation"), error.getMessage());
        verify(invocations).recordFailure(eq(1L), eq(AiInvocationService.STAGE_SCREEN), any(),
                anyString(), anyString(), anyLong());
        verify(invocations, never()).recordSuccess(any(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void nonBooleanViolationFieldIsTreatedAsFailure() {
        queueReplies("{\"violation\":\"true\",\"confidence\":0.99}");

        assertThrows(BizException.class, () -> screen("whatever"));
        verify(invocations).recordFailure(any(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void nonJsonReplyIsTreatedAsFailureAndNeverAutoPasses() {
        queueReplies("I think this call is probably fine");

        BizException error = assertThrows(BizException.class, () -> screen("whatever"));

        assertTrue(error.getMessage().contains("JSON"), error.getMessage());
        verify(invocations).recordFailure(any(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void lowConfidenceViolationIsMarkedDegraded() {
        properties.setMinConfidence(0.6);
        queueReplies(LOW_CONFIDENCE_VIOLATION);

        AiScreenResult result = screen("maybe a threat");

        assertTrue(result.violation(), "保留模型原始判定，便于人工对照");
        assertTrue(result.degraded(), "低置信度违规必须降级，不得自动放行");
        assertNotNull(result.degradedReason());
        assertTrue(result.degradedReason().contains("低于阈值"), result.degradedReason());
    }

    @Test
    void highConfidenceViolationIsNotDegraded() {
        properties.setMinConfidence(0.6);
        queueReplies(VIOLATION);

        AiScreenResult result = screen("you idiot");

        assertTrue(result.violation());
        assertFalse(result.degraded());
    }

    @Test
    void longTranscriptIsChunkedAndViolationInLaterChunkWins() {
        properties.setChunkChars(500);
        properties.setMaxChunks(6);
        // 无空格/换行 => 恰好切 3 块：每块 500 字符、重叠 50 字符
        String transcript = "x".repeat(1200);
        queueReplies(COMPLIANT, COMPLIANT, VIOLATION);

        AiScreenResult result = screen(transcript);

        assertTrue(result.violation(), "任一块违规即整体违规");
        assertEquals(0.92, result.confidence(), 1e-9);
        assertTrue(result.reason().contains("第 3/3 块"), result.reason());
        verify(invocations, times(3)).recordSuccess(any(), anyString(), any(), anyString(), anyString(), anyLong());

        ArgumentCaptor<String> requests = ArgumentCaptor.forClass(String.class);
        verify(invocations, times(3)).recordSuccess(any(), anyString(), any(), requests.capture(),
                anyString(), anyLong());
        List<String> bodies = requests.getAllValues();
        assertTrue(bodies.get(0).contains("第 1/3 块"), bodies.get(0));
        assertTrue(bodies.get(1).contains("第 2/3 块"), bodies.get(1));
        assertTrue(bodies.get(2).contains("第 3/3 块"), bodies.get(2));
    }

    @Test
    void chunkingStopsAtMaxChunks() {
        properties.setChunkChars(500);
        properties.setMaxChunks(2);
        String transcript = "y".repeat(5000);
        queueReplies(COMPLIANT, COMPLIANT);

        AiScreenResult result = screen(transcript);

        assertFalse(result.violation());
        assertFalse(result.degraded());
        verify(invocations, times(2)).recordSuccess(any(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void chunkFailureAbortsWholeScreeningSoPipelineCanDegrade() {
        properties.setChunkChars(500);
        properties.setMaxChunks(6);
        String transcript = "z".repeat(1200);
        // 第一块成功、第二块返回不可解析内容 => 整体失败（不能因为第一块合规就放行）
        queueReplies(COMPLIANT, "{not json");

        assertThrows(BizException.class, () -> screen(transcript));
        // 第一块成功留痕、第二块失败留痕；第二块失败后整体中止，不会自动放行
        verify(invocations, times(1)).recordSuccess(any(), anyString(), any(), anyString(), anyString(), anyLong());
        verify(invocations, times(1)).recordFailure(any(), anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void splitChunksAppliesOverlapAndKeepsProgress() {
        String transcript = "x".repeat(250);

        List<String> chunks = SemanticScreeningService.splitChunks(transcript, 100, 10);

        assertEquals(3, chunks.size());
        assertEquals(100, chunks.get(0).length());
        assertTrue(chunks.get(1).startsWith(chunks.get(0).substring(90)), "相邻块应有 10 字符重叠");
        assertTrue(chunks.get(2).startsWith(chunks.get(1).substring(90)), "最后一块同样与前一块重叠");
        assertTrue(transcript.endsWith(chunks.get(chunks.size() - 1)), "最后一块必须覆盖文本末尾");
    }

    @Test
    void splitChunksPrefersNewlineBoundary() {
        String transcript = "a".repeat(70) + "\n" + "b".repeat(200);

        List<String> chunks = SemanticScreeningService.splitChunks(transcript, 100, 10);

        assertEquals(70, chunks.get(0).length(), "切点应回退到换行处，避免句子被切断");
        assertFalse(chunks.get(0).contains("\n"));
    }

    @Test
    void shortTranscriptIsSingleChunkWithoutOverlapLogic() {
        assertEquals(List.of("short text"), SemanticScreeningService.splitChunks("short text", 12000, 7));
        // 重叠约 10%，最多 500 字符
        assertEquals(500, SemanticScreeningService.overlapChars(12000));
    }
}
