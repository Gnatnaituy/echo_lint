package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.config.ScreenModelProperties;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.service.AudioTranscriptionService.TranscriptionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 流水线编排：AI 复筛不可用时的降级路径（DFA 已命中，不能漏审）与判定模型留痕
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineServiceTest {

    @Mock private RecordingRepository recordingRepository;
    @Mock private PipelineLogRepository pipelineLogRepository;
    @Mock private DictionaryWordRepository dictionaryWordRepository;
    @Mock private AudioTranscriptionService audioTranscriptionService;
    @Mock private DfaService dfaService;
    @Mock private SemanticScreeningService semanticScreeningService;
    @Mock private ScreenModelService screenModelService;
    @Mock private CorpusService corpusService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PipelineService pipeline;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setUploadDir(tempDir.toString());

        ScreenModelProperties screenProperties = new ScreenModelProperties();
        screenProperties.setFewShotCount(5);

        pipeline = new PipelineService(
                recordingRepository, pipelineLogRepository, dictionaryWordRepository,
                audioTranscriptionService, dfaService, semanticScreeningService, screenModelService,
                corpusService, appProperties, screenProperties, objectMapper);
    }

    private Recording stubRecording() {
        Recording r = new Recording();
        r.setId(1L);
        r.setFileName("call.mp3");
        r.setFilePath("stored.mp3");
        r.setFileSize(100L);
        r.setStatus(RecordingStatus.PENDING);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(r));
        when(recordingRepository.save(any(Recording.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineLogRepository.save(any(PipelineLog.class))).thenAnswer(inv -> inv.getArgument(0));
        return r;
    }

    private void stubTranscriptionAndHit() {
        when(audioTranscriptionService.transcribe(any(Path.class))).thenReturn(
                new TranscriptionOutcome("you stupid bitch", "[]", 1, null, "en", 12, false));
        when(dfaService.match(anyString())).thenReturn(List.of(new DfaHit("bitch", 0, 5)));
        when(dictionaryWordRepository.findByWordIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(corpusService.getFewShotExamples(anyInt())).thenReturn(List.of());
        when(screenModelService.active()).thenReturn(new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", "/chat/completions",
                true, true, null, java.util.Map.of(), 30, null));
    }

    @Test
    void screeningFailureDegradesToManualReview() throws Exception {
        Recording r = stubRecording();
        stubTranscriptionAndHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any()))
                .thenThrow(new BizException("当前复筛模型「DeepSeek · deepseek-flash」未配置 API Key"));

        pipeline.run(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus(), "复筛不可用不能自动放行，也不能直接失败");
        assertNotNull(r.getProcessedTime());
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertTrue(ai.path("degraded").asBoolean(), "aiResultJson 应标记为降级");
        assertTrue(ai.path("reason").asText().contains("API Key"), "失败原因应留痕");
    }

    @Test
    void screeningVerdictRecordsWhichModelJudged() throws Exception {
        Recording r = stubRecording();
        stubTranscriptionAndHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any())).thenReturn(
                new AiScreenResult(true, com.echolint.domain.ViolationType.INSULT, "辱骂", "直接辱骂客户",
                        0.92, "you stupid bitch", "deepseek", "DeepSeek", "deepseek-flash"));

        pipeline.run(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus());
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertEquals("deepseek", ai.path("screenProvider").asText());
        assertEquals("DeepSeek", ai.path("screenProviderLabel").asText());
        assertEquals("deepseek-flash", ai.path("screenModel").asText());
        assertFalse(ai.path("degraded").asBoolean());
        assertEquals(0.92, ai.path("confidence").asDouble(), 1e-6);
    }

    @Test
    void dfaMissStaysCompliantAndSkipsScreening() {
        Recording r = stubRecording();
        when(audioTranscriptionService.transcribe(any(Path.class))).thenReturn(
                new TranscriptionOutcome("have a nice day", "[]", 1, null, "en", 8, false));
        when(dfaService.match(anyString())).thenReturn(List.of());

        pipeline.run(1L);

        assertEquals(RecordingStatus.COMPLIANT, r.getStatus());
        assertNull(r.getAiResultJson(), "初筛未命中不应调用 AI 复筛");
    }
}
