package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.dfa.DfaHit;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 稽核流水线编排：转写 -> DFA 初筛 -> AI 语义复筛 -> 归档/待复检
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
    private final CorpusService corpusService;
    private final com.echolint.config.AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Async("pipelineExecutor")
    public void run(Long recordingId) {
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
            recordingRepository.save(recording);
            if (hits.isEmpty()) {
                finishCompliant(recording, "DFA 初筛未命中敏感词，自动通过");
                return;
            }
            String words = hits.stream().map(DfaHit::word).distinct().collect(Collectors.joining(", "));
            logStage(recordingId, "DFA", "WARN",
                    "DFA 初筛命中 " + hits.size() + " 处（" + words + "），进入 AI 语义复筛");
            incrementHitCounts(hits);

            // 3. AI 语义复筛（few-shot 带人工复核语料；双声道附带说话人标注）
            updateStatus(recording, RecordingStatus.AI_CHECKING);
            var examples = corpusService.getFewShotExamples(5);
            var ai = semanticScreeningService.screen(outcome.transcript(), outcome.segmentsJson(), hits, examples);
            recording.setAiResultJson(objectMapper.writeValueAsString(MapUtil.of(
                    "violation", ai.violation(),
                    "violationType", ai.typeCode(),
                    "violationTypeLabel", ai.typeLabel(),
                    "reason", ai.reason(),
                    "confidence", ai.confidence(),
                    "targetSentence", ai.targetSentence())));
            recordingRepository.save(recording);

            if (!ai.violation()) {
                finishCompliant(recording, "AI 复筛判定命中词不构成违规，自动通过");
                return;
            }
            // 4. 初筛 + 复筛均违规 -> 人工复检
            recording.setStatus(RecordingStatus.NEEDS_REVIEW);
            recording.setProcessedTime(LocalDateTime.now());
            recordingRepository.save(recording);
            logStage(recordingId, "AI", "ERROR",
                    "AI 复筛判定违规（" + ai.typeLabel() + "，置信度 " + String.format("%.2f", ai.confidence())
                            + "）：" + ai.reason() + " → 进入人工复检");
        } catch (BizException e) {
            fail(recording, e.getMessage());
        } catch (Exception e) {
            log.error("流水线处理录音 {} 异常", recordingId, e);
            fail(recording, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
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