package com.echolint.service;

import com.echolint.domain.ViolationType;
import com.echolint.entity.DictionaryWord;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.PipelineLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 词典挖掘（Batch 3a 回归）：共用供应商、配额校验、usage/成本留痕、失败不影响主流程。
 */
@ExtendWith(MockitoExtension.class)
class WordMiningServiceTest {

    @Mock
    private ScreenModelService screenModelService;
    @Mock
    private AiInvocationService invocationService;
    @Mock
    private DictionaryWordRepository dictionaryWordRepository;
    @Mock
    private PipelineLogRepository pipelineLogRepository;
    @Mock
    private AiModelCaller aiModelCaller;
    @Mock
    private AiQuotaService quotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WordMiningService service;
    private Recording recording;

    @BeforeEach
    void setUp() {
        service = new WordMiningService(screenModelService, invocationService, objectMapper,
                dictionaryWordRepository, pipelineLogRepository, aiModelCaller, quotaService);
        recording = new Recording();
        recording.setId(5L);
        recording.setTranscript("you idiot, dumbass");
        when(screenModelService.active()).thenReturn(model());
    }

    private ScreenModelService.ActiveModel model() {
        return new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
                "/chat/completions", true, true, null, Map.of(), 30, null,
                new BigDecimal("0.001"), new BigDecimal("0.002"));
    }

    private JsonNode reply(String keywordsJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", keywordsJson);
        root.putObject("usage").put("prompt_tokens", 200).put("completion_tokens", 20);
        return root;
    }

    @Test
    void minesKeywordsAndRecordsUsage() {
        when(aiModelCaller.call(any(), anyString(), anyString()))
                .thenReturn(reply("{\"keywords\":[\"idiot\",\"dumbass\"]}"));
        when(dictionaryWordRepository.findByWordIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(dictionaryWordRepository.save(any(DictionaryWord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineLogRepository.save(any(PipelineLog.class))).thenAnswer(inv -> inv.getArgument(0));

        int added = service.mineAndSuggest(recording, ViolationType.INSULT);

        assertEquals(2, added);
        verify(quotaService).checkBeforeCall(any());

        ArgumentCaptor<AiUsage> usageCaptor = ArgumentCaptor.forClass(AiUsage.class);
        verify(invocationService).recordSuccess(eq(5L), eq(AiInvocationService.STAGE_MINING), any(),
                anyString(), anyString(), anyLong(), usageCaptor.capture());
        AiUsage usage = usageCaptor.getValue();
        assertEquals(200, usage.inputTokens());
        assertEquals(20, usage.outputTokens());
        // 200/1000*0.001 + 20/1000*0.002 = 0.00024 USD = 240 微美元
        assertEquals(240L, usage.costMicros());
    }

    @Test
    void quotaExceededDegradesWithoutCallingModel() {
        doThrow(new BizException("AI 调用频率超限", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS))
                .when(quotaService).checkBeforeCall(any());
        when(pipelineLogRepository.save(any(PipelineLog.class))).thenAnswer(inv -> inv.getArgument(0));

        int added = service.mineAndSuggest(recording, ViolationType.INSULT);

        assertEquals(0, added);
        verify(aiModelCaller, never()).call(any(), anyString(), anyString());
        verify(invocationService).recordFailure(eq(5L), eq(AiInvocationService.STAGE_MINING), any(),
                anyString(), anyString(), anyLong());
        ArgumentCaptor<PipelineLog> logCaptor = ArgumentCaptor.forClass(PipelineLog.class);
        verify(pipelineLogRepository).save(logCaptor.capture());
        assertTrue(logCaptor.getValue().getMessage().contains("词挖掘失败"),
                logCaptor.getValue().getMessage());
    }

    @Test
    void missingApiKeyDegradesWithoutCallingModel() {
        doThrow(new BizException("当前复筛模型「DeepSeek · deepseek-flash」未配置 API Key"))
                .when(screenModelService).requireUsable(any());
        when(pipelineLogRepository.save(any(PipelineLog.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(0, service.mineAndSuggest(recording, ViolationType.INSULT));
        verify(aiModelCaller, never()).call(any(), anyString(), anyString());
        verify(invocationService).recordFailure(any(), anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void existingWordsAreNotDuplicated() {
        when(aiModelCaller.call(any(), anyString(), anyString()))
                .thenReturn(reply("{\"keywords\":[\"idiot\"]}"));
        when(dictionaryWordRepository.findByWordIgnoreCase(anyString()))
                .thenReturn(Optional.of(new DictionaryWord()));

        assertEquals(0, service.mineAndSuggest(recording, ViolationType.INSULT));
        verify(dictionaryWordRepository, never()).save(any(DictionaryWord.class));
    }

    @Test
    void emptyKeywordsReturnZero() {
        when(aiModelCaller.call(any(), anyString(), anyString()))
                .thenReturn(reply("{\"keywords\":[]}"));

        assertEquals(0, service.mineAndSuggest(recording, ViolationType.INSULT));
        verify(dictionaryWordRepository, never()).save(any(DictionaryWord.class));
    }
}
