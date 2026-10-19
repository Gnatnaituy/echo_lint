package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.NotifyEvent;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 稽核流水线编排：转写 -> DFA 初筛 -> （AI 语义复筛） -> 归档/待复检
 *
 * <p>Batch 2b-2 起支持合规抽样（{@code app.sample.dfa-miss-rate}）：DFA 未命中的录音按概率
 * 也送进 AI 语义复筛，用于发现词典漏检。抽样录音的 AI 结论如果是「合规」才自动归档，
 * AI 不可用/降级则一律转人工复检，不允许静默放行。</p>
 *
 * 注意：本类有 @Async 方法，**不要实现任何接口**，否则 Spring 会用 JDK 动态代理，
 * 导致其它 Bean 按本具体类型注入失败（启动恢复逻辑见 PipelineRecoveryService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final DictionaryWordRepository dictionaryWordRepository;
    private final AudioTranscriptionService audioTranscriptionService;
    private final DfaService dfaService;
    private final SemanticScreeningService semanticScreeningService;
    private final ScreenModelService screenModelService;
    private final CorpusService corpusService;
    private final com.echolint.config.AppProperties appProperties;
    private final com.echolint.config.ScreenModelProperties screenModelProperties;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    /** 正在处理的录音 id（进程内），用于防止重复触发 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public boolean isInFlight(Long recordingId) {
        return inFlight.contains(recordingId);
    }

    @Async("pipelineExecutor")
    public void run(Long recordingId) {
        if (!inFlight.add(recordingId)) {
            log.warn("录音 {} 正在处理中，忽略重复触发", recordingId);
            return;
        }
        try {
            process(recordingId);
        } finally {
            inFlight.remove(recordingId);
        }
    }

    private void process(Long recordingId) {
        Recording recording = recordingRepository.findById(recordingId).orElse(null);
        if (recording == null) {
            log.warn("流水线找不到录音 {}", recordingId);
            return;
        }
        logStage(recordingId, "PIPELINE", "INFO", "开始处理，待执行步骤: 转写 -> DFA 初筛 -> AI 复筛");
        try {
            // 1. 转写（双声道自动分轨：左右声道分别转写后按时间对齐）
            updateStatus(recording, RecordingStatus.TRANSCRIBING);
            var outcome = audioTranscriptionService.transcribe(resolvePath(recording));
            recording.setTranscript(outcome.transcript());
            recording.setSegmentsJson(outcome.segmentsJson());
            recording.setChannelCount(outcome.channelCount());
            recording.setChannelFilesJson(outcome.channelFilesJson());
            recording.setLanguage(outcome.language());
            recording.setDurationSeconds(outcome.durationSeconds());
            logStage(recordingId, "TRANSCRIBE", "INFO",
                    (outcome.stereo() ? "双声道转写完成（左声道=坐席 / 右声道=客户）" : "单路转写完成")
                            + "：时长 " + outcome.durationSeconds() + "s，语言 " + outcome.language()
                            + "，分段 " + segmentCount(outcome.segmentsJson()) + " 段，文本 "
                            + outcome.transcript().length() + " 字符");

            // 2. DFA 初筛
            updateStatus(recording, RecordingStatus.DFA_CHECKING);
            List<DfaHit> hits = dfaService.match(outcome.transcript());
            recording.setHitCount(hits.size());
            recording.setDfaHitsJson(objectMapper.writeValueAsString(hits));

            // DFA 未命中：默认直接归档；配置了抽样比例时按概率进入 AI 抽样复核
            boolean sampled = false;
            if (hits.isEmpty()) {
                if (!shouldSampleDfaMiss()) {
                    recordingRepository.save(recording);
                    finishCompliant(recording, "DFA 初筛未命中敏感词，自动通过");
                    return;
                }
                sampled = true;
                recording.setSampled(true);
                recordingRepository.save(recording);
                logStage(recordingId, "DFA", "INFO",
                        "抽样复核：DFA 初筛未命中敏感词，按 app.sample.dfa-miss-rate="
                                + String.format("%.2f", appProperties.dfaMissSampleRate())
                                + " 抽中进入 AI 语义复筛");
            } else {
                recordingRepository.save(recording);
                String words = hits.stream().map(DfaHit::word).distinct().collect(Collectors.joining(", "));
                logStage(recordingId, "DFA", "WARN",
                        "DFA 初筛命中 " + hits.size() + " 处（" + words + "），进入 AI 语义复筛");
                incrementHitCounts(hits);
            }

            // 3. AI 语义复筛（few-shot 带人工复核语料；双声道附带说话人标注；模型可在系统设置中切换）
            updateStatus(recording, RecordingStatus.AI_CHECKING);
            AiScreenResult ai;
            try {
                var activeModel = screenModelService.active();
                logStage(recordingId, "AI", "INFO", "使用模型 " + activeModel.display() + " 进行语义复筛"
                        + (sampled ? "（抽样复核）" : ""));
                var examples = corpusService.getFewShotExamples(screenModelProperties.getFewShotCount());
                ai = semanticScreeningService.screen(recordingId, outcome.transcript(), outcome.segmentsJson(),
                        hits, examples);
            } catch (Exception e) {
                // 复筛不可用（模型未配密钥 / 网络 / 额度 / 返回格式异常…）时不能漏审：
                // DFA 已命中或抽样录音，直接降级转人工复检，失败原因写进 aiResultJson
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("录音 {} 语义复筛不可用，降级转人工复检: {}", recordingId, reason);
                ai = AiScreenResult.failed(reason);
            }
            // 降级来源：AI 不可用（failed）或「判定违规但置信度低于阈值」（SemanticScreeningService 标记）
            boolean degraded = ai.degraded();
            recording.setAiResultJson(objectMapper.writeValueAsString(MapUtil.of(
                    "violation", ai.violation(),
                    "violationType", ai.typeCode(),
                    "violationTypeLabel", ai.typeLabel(),
                    "reason", ai.reason(),
                    "confidence", ai.confidence(),
                    "targetSentence", ai.targetSentence(),
                    "screenProvider", ai.providerId(),
                    "screenProviderLabel", ai.providerLabel(),
                    "screenModel", ai.model(),
                    "degraded", degraded)));
            recordingRepository.save(recording);

            if (!degraded && !ai.violation()) {
                finishCompliant(recording, sampled
                        ? "抽样复核：AI 判定合规，自动通过"
                        : "AI 复筛判定命中词不构成违规，自动通过");
                return;
            }
            // 4. 初筛 + 复筛均违规（或复筛不可用/低置信度）-> 人工复检
            recording.setStatus(RecordingStatus.NEEDS_REVIEW);
            recording.setProcessedTime(LocalDateTime.now());
            recordingRepository.save(recording);
            String sampleTag = sampled ? "抽样复核：" : "";
            if (degraded) {
                String why = ai.violation()
                        ? "AI 判定违规但置信度不足，降级转入人工复检"
                        : "AI 复筛不可用，降级转入人工复检";
                logStage(recordingId, "AI", "WARN", sampleTag + why + "：" + ai.reason());
            } else {
                logStage(recordingId, "AI", "ERROR", sampleTag
                        + "AI 复筛判定违规（" + ai.typeLabel() + "，置信度 "
                        + String.format("%.2f", ai.confidence())
                        + "，模型 " + ai.providerLabel() + " · " + ai.model() + "）：" + ai.reason()
                        + " → 进入人工复检");
            }
        } catch (BizException e) {
            fail(recording, e.getMessage());
        } catch (Exception e) {
            log.error("流水线处理录音 {} 异常", recordingId, e);
            fail(recording, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * DFA 未命中时的抽样判定：rate ≤ 0 不抽样、rate ≥ 1 必抽样，其余按 Math.random() 概率。
     * 概率测算在服务端完成，调用方只关心「是否抽样」。
     */
    private boolean shouldSampleDfaMiss() {
        double rate = appProperties.dfaMissSampleRate();
        if (rate <= 0.0) {
            return false;
        }
        if (rate >= 1.0) {
            return true;
        }
        return Math.random() < rate;
    }

    private void finishCompliant(Recording recording, String msg) {
        recording.setStatus(RecordingStatus.COMPLIANT);
        recording.setProcessedTime(LocalDateTime.now());
        recordingRepository.save(recording);
        logStage(recording.getId(), "PIPELINE", "INFO", msg + "，归档为 COMPLIANT");
    }

    private void fail(Recording recording, String message) {
        recording.setStatus(RecordingStatus.FAILED);
        recording.setErrorMessage(message);
        recording.setProcessedTime(LocalDateTime.now());
        recordingRepository.save(recording);
        logStage(recording.getId(), "PIPELINE", "ERROR", "处理失败: " + message);
        // 失败告警是旁路能力：NotificationService 内部已吞异常，这里不阻塞流水线收尾
        notificationService.notify(NotifyEvent.RECORDING_FAILED, recording.getId(), "录音处理失败",
                "录音 #" + recording.getId() + "（" + recording.getFileName() + "）处理失败：" + message, null);
    }

    private void updateStatus(Recording recording, RecordingStatus status) {
        recording.setStatus(status);
        recordingRepository.save(recording);
    }

    private void incrementHitCounts(List<DfaHit> hits) {
        for (DfaHit hit : hits) {
            dictionaryWordRepository.findByWordIgnoreCase(hit.word()).ifPresent(w -> {
                w.setHitCount(w.getHitCount() + 1);
                dictionaryWordRepository.save(w);
            });
        }
    }

    private Path resolvePath(Recording recording) {
        return appProperties.absoluteUploadDir().resolve(recording.getFilePath());
    }

    /** 分段数（用于日志） */
    private int segmentCount(String segmentsJson) {
        try {
            return objectMapper.readTree(segmentsJson == null ? "[]" : segmentsJson).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void logStage(Long recordingId, String stage, String level, String message) {
        PipelineLog l = new PipelineLog();
        l.setRecordingId(recordingId);
        l.setStage(stage);
        l.setLevel(level);
        l.setMessage(message);
        pipelineLogRepository.save(l);
    }

    /** 简单 Map 工具，避免额外依赖 */
    private static final class MapUtil {
        static java.util.Map<String, Object> of(Object... kv) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return m;
        }
    }
}
