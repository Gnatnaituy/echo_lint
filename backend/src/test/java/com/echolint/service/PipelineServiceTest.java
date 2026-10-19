package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.config.ScreenModelProperties;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ViolationType;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流水线编排：AI 复筛不可用时的降级路径（DFA 已命中/抽样录音都不能漏审）、判定模型留痕、
 * 合规抽样分支（Batch 2b-2）与失败告警。
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
    private AppProperties appProperties;
    private PipelineService pipeline;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
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
        when(screenModelService.active()).thenReturn(activeModel());
    }

    private void stubTranscriptionWithoutHit() {
        when(audioTranscriptionService.transcribe(any(Path.class))).thenReturn(
                new TranscriptionOutcome("have a nice day", "[]", 1, null, "en", 8, false));
        when(dfaService.match(anyString())).thenReturn(List.of());
        when(corpusService.getFewShotExamples(anyInt())).thenReturn(List.of());
        when(screenModelService.active()).thenReturn(activeModel());
    }

    private ScreenModelService.ActiveModel activeModel() {
        return new ScreenModelService.ActiveModel(
                "deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", "/chat/completions",
                true, true, null, java.util.Map.of(), 30, null,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
    }

    private List<PipelineLog> pipelineLogs() {
        ArgumentCaptor<PipelineLog> captor = ArgumentCaptor.forClass(PipelineLog.class);
        verify(pipelineLogRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private void assertLogged(String fragment) {
        assertTrue(pipelineLogs().stream().anyMatch(l -> l.getMessage() != null && l.getMessage().contains(fragment)),
                "流水线日志应包含：" + fragment);
    }

    @Test
    void screeningFailureDegradesToManualReview() throws Exception {
        Recording r = stubRecording();
        stubTranscriptionAndHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any()))
                .thenThrow(new BizException("当前复筛模型「DeepSeek · deepseek-flash」未配置 API Key"));

        pipeline.processNow(1L);

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
                new AiScreenResult(true, ViolationType.INSULT, "辱骂", "直接辱骂客户",
                        0.92, "you stupid bitch", "deepseek", "DeepSeek", "deepseek-flash"));

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus());
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertEquals("deepseek", ai.path("screenProvider").asText());
        assertEquals("DeepSeek", ai.path("screenProviderLabel").asText());
        assertEquals("deepseek-flash", ai.path("screenModel").asText());
        assertEquals("A", ai.path("screenVariant").asText(), "未开启 A/B 时默认 A 组");
        assertFalse(ai.path("degraded").asBoolean());
        assertEquals(0.92, ai.path("confidence").asDouble(), 1e-6);
    }

    @Test
    void screeningVerdictRecordsAbVariant() throws Exception {
        Recording r = stubRecording();
        stubTranscriptionAndHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any())).thenReturn(
                new AiScreenResult(true, ViolationType.INSULT, "辱骂", "B 组模型判定",
                        0.91, "you idiot", "stub-b", "Stub B", "b-model", false, null, "B"));

        pipeline.processNow(1L);

        var ai = objectMapper.readTree(r.getAiResultJson());
        assertEquals("B", ai.path("screenVariant").asText(), "aiResultJson 应记录 A/B 组别");
        assertEquals("stub-b", ai.path("screenProvider").asText());
        assertEquals("b-model", ai.path("screenModel").asText());
    }

    @Test
    void dfaMissStaysCompliantAndSkipsScreening() {
        Recording r = stubRecording();
        stubTranscriptionWithoutHit();

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.COMPLIANT, r.getStatus());
        assertFalse(r.isSampled(), "默认抽样率为 0，不应标记为抽样");
        assertNull(r.getAiResultJson(), "初筛未命中不应调用 AI 复筛");
        verify(semanticScreeningService, never()).screen(any(), anyString(), any(), any(), any());
    }

    @Test
    void sampledDfaMissWithCompliantAiVerdictIsArchivedCompliant() throws Exception {
        appProperties.getSample().setDfaMissRate(1.0);
        Recording r = stubRecording();
        stubTranscriptionWithoutHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any())).thenReturn(
                new AiScreenResult(false, ViolationType.NONE, "无", "no violation", 0.88, "",
                        "deepseek", "DeepSeek", "deepseek-flash"));

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.COMPLIANT, r.getStatus(), "抽样录音 AI 判合规应自动归档");
        assertTrue(r.isSampled(), "抽样命中的录音应标记 sampled=true");
        assertNotNull(r.getProcessedTime());
        assertEquals(0, r.getHitCount(), "抽样录音 DFA 无命中");
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertFalse(ai.path("violation").asBoolean());
        assertFalse(ai.path("degraded").asBoolean());
        assertLogged("抽样复核：AI 判定合规");
    }

    @Test
    void sampledDfaMissWithViolationGoesNeedsReview() throws Exception {
        appProperties.getSample().setDfaMissRate(1.0);
        Recording r = stubRecording();
        stubTranscriptionWithoutHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any())).thenReturn(
                new AiScreenResult(true, ViolationType.FRAUD, "诈骗诱导", "elevated risk language",
                        0.9, "wire me the money", "deepseek", "DeepSeek", "deepseek-flash"));

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus(), "抽样录音 AI 判违规应转人工复检");
        assertTrue(r.isSampled());
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertTrue(ai.path("violation").asBoolean());
        assertFalse(ai.path("degraded").asBoolean());
        assertLogged("抽样复核");
    }

    @Test
    void sampledDfaMissWithoutAiMustNotAutoPass() throws Exception {
        appProperties.getSample().setDfaMissRate(1.0);
        Recording r = stubRecording();
        stubTranscriptionWithoutHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any()))
                .thenThrow(new BizException("当前复筛模型「DeepSeek · deepseek-flash」未配置 API Key"));

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus(), "抽样录音 AI 不可用时不能自动放行");
        assertTrue(r.isSampled());
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertTrue(ai.path("degraded").asBoolean(), "AI 不可用应标记 degraded");
        assertFalse(ai.path("violation").asBoolean());
    }

    @Test
    void lowConfidenceViolationVerdictIsTreatedAsDegraded() throws Exception {
        Recording r = stubRecording();
        stubTranscriptionAndHit();
        when(semanticScreeningService.screen(any(), anyString(), any(), any(), any())).thenReturn(
                new AiScreenResult(true, ViolationType.THREAT, "威胁", "maybe a threat", 0.42, "",
                        "deepseek", "DeepSeek", "deepseek-flash").withDegraded("置信度低于阈值"));

        pipeline.processNow(1L);

        assertEquals(RecordingStatus.NEEDS_REVIEW, r.getStatus(), "低置信度违规不得自动放行");
        var ai = objectMapper.readTree(r.getAiResultJson());
        assertTrue(ai.path("degraded").asBoolean());
    }

    @Test
    void pipelineFailureMarksRecordingFailedAndThrowsForTaskLayer() {
        Recording r = stubRecording();
        when(audioTranscriptionService.transcribe(any(Path.class)))
                .thenThrow(new RuntimeException("ffmpeg crashed"));

        BizException e = assertThrows(BizException.class, () -> pipeline.processNow(1L));

        assertEquals(RecordingStatus.FAILED, r.getStatus(), "失败照旧落 FAILED");
        assertEquals("ffmpeg crashed", r.getErrorMessage());
        assertTrue(e.getMessage().contains("ffmpeg crashed"), "异常向上抛出，供任务层累计重试/死信");
    }

    @Test
    void missingRecordingIsReportedAsNotFoundWithoutRetryChurn() {
        when(recordingRepository.findById(9L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> pipeline.processNow(9L));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void successfulPipelineDoesNotThrow() {
        Recording r = stubRecording();
        stubTranscriptionWithoutHit();

        assertDoesNotThrow(() -> pipeline.processNow(1L));

        assertEquals(RecordingStatus.COMPLIANT, r.getStatus());
    }

    @Test
    void processNowClearsPreviousErrorAndSampleFlag() {
        Recording r = stubRecording();
        r.setErrorMessage("上次失败");
        r.setSampled(true);
        stubTranscriptionWithoutHit();

        pipeline.processNow(1L);

        assertNull(r.getErrorMessage(), "新一次处理应清空上一轮错误，避免重试成功后残留");
        assertFalse(r.isSampled(), "非抽样路径不应残留上一轮的 sampled 标记");
    }
}
