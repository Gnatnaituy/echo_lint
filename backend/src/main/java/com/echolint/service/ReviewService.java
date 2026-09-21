package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.ReviewResult;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ViolationType;
import com.echolint.dto.ReviewRequest;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 人工复检：确认违规 / 判定误报；结论回馈语料库并触发词典挖掘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final CorpusService corpusService;
    private final WordMiningService wordMiningService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Recording review(Long id, ReviewRequest request) {
        Recording recording = recordingRepository.findById(id)
                .orElseThrow(() -> new BizException("录音不存在: " + id, HttpStatus.NOT_FOUND));

        if (recording.getStatus() != RecordingStatus.NEEDS_REVIEW) {
            throw new BizException("该录音当前状态为 " + recording.getStatus() + "，不可复检（仅待复检录音可操作）");
        }

        ReviewResult result = ReviewResult.valueOf(safeEnum(request.result(), ReviewResult.class, "CONFIRMED_VIOLATION"));
        String reviewer = StringUtils.hasText(request.reviewer()) ? request.reviewer() : "admin";
        String comment = request.comment();

        if (result == ReviewResult.CONFIRMED_VIOLATION) {
            ViolationType type = ViolationType.fromCode(request.violationType());
            if (type == ViolationType.NONE) {
                throw new BizException("确认违规时必须选择违规类型");
            }
            recording.setStatus(RecordingStatus.VIOLATION_CONFIRMED);
            recording.setViolationType(type.name());
            recording.setViolationTypeLabel(type.getLabel());
            // 结论回馈语料库（违规样本）
            corpusService.append(recording, CorpusLabel.VIOLATION, type.name(),
                    StringUtils.hasText(comment) ? comment : extractAiReason(recording));
            recording.setReviewResult(ReviewResult.CONFIRMED_VIOLATION);
            logStage(recording.getId(), "REVIEW", "WARN",
                    "人工复检确认违规（" + type.getLabel() + "）：" + (StringUtils.hasText(comment) ? comment : "无备注"));
            // 从确认违规录音挖掘新词典词（停用待审核），异常不阻塞复检
            wordMiningService.mineAndSuggest(recording, type);
        } else {
            recording.setStatus(RecordingStatus.FALSE_POSITIVE);
            recording.setReviewResult(ReviewResult.FALSE_POSITIVE);
            // 结论回馈语料库（合规样本）
            corpusService.append(recording, CorpusLabel.COMPLIANT, null,
                    StringUtils.hasText(comment) ? comment : extractAiReason(recording));
            logStage(recording.getId(), "REVIEW", "INFO",
                    "人工复检判定为误报：" + (StringUtils.hasText(comment) ? comment : "无备注"));
        }

        recording.setReviewComment(comment);
        recording.setReviewer(reviewer);
        recording.setReviewTime(LocalDateTime.now());
        Recording saved = recordingRepository.save(recording);
        log.info("录音 {} 复检完成：{}（reviewer={}）", id, result, reviewer);
        return saved;
    }

    private String extractAiReason(Recording recording) {
        if (!StringUtils.hasText(recording.getAiResultJson())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(recording.getAiResultJson());
            return node.path("reason").asText(null);
        } catch (Exception e) {
            return null;
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

    private static <E extends Enum<E>> String safeEnum(String raw, Class<E> clazz, String defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            Enum.valueOf(clazz, raw.trim());
            return raw.trim();
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}