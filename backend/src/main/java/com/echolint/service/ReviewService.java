package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.domain.CorpusLabel;
import com.echolint.domain.NotifyEvent;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewResult;
import com.echolint.domain.ViolationType;
import com.echolint.dto.ReviewRequest;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.entity.ReviewRecord;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.security.AppUserDetails;
import com.echolint.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工复检：确认违规 / 判定误报。
 *
 * <p>支持多人复核（{@code app.review.required-approvals}）：每次提交写一条 {@code review_records}，
 * 达到所需人数才落最终结论、回馈语料库，并在事务提交后异步触发词典挖掘；未达标时保持 NEEDS_REVIEW。
 * 同一用户不能连续复核同一条录音（409）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final CorpusService corpusService;
    private final AsyncWordMiningTrigger miningTrigger;
    private final ObjectMapper objectMapper;
    private final ReviewRecordRepository reviewRecordRepository;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final NotificationService notificationService;

    @Transactional
    public Recording review(Long id, ReviewRequest request) {
        Recording recording = recordingRepository.findById(id)
                .orElseThrow(() -> new BizException("录音不存在: " + id, HttpStatus.NOT_FOUND));
        AppUserDetails currentUser = currentUserOrThrow();

        if (recording.getStatus() != RecordingStatus.NEEDS_REVIEW) {
            // 并发提交时后提交者会命中这里（状态已非 NEEDS_REVIEW）
            throw new BizException("该录音当前状态为 " + recording.getStatus()
                    + "，不可复检（仅待复检录音可操作）", HttpStatus.CONFLICT);
        }

        ReviewResult result = ReviewResult.valueOf(safeEnum(request.result(), ReviewResult.class, "CONFIRMED_VIOLATION"));
        ViolationType type = ViolationType.NONE;
        if (result == ReviewResult.CONFIRMED_VIOLATION) {
            type = ViolationType.fromCode(request.violationType());
            if (type == ViolationType.NONE) {
                throw new BizException("确认违规时必须选择违规类型");
            }
        }

        int requiredApprovals = appProperties.reviewRequiredApprovals();
        int alreadyReviewed = Math.max(0, recording.getReviewCount());
        int round = alreadyReviewed + 1;

        // 双人复核：同一用户不能连续复核同一条录音
        if (requiredApprovals > 1 && alreadyReviewed > 0) {
            reviewRecordRepository.findTopByRecordingIdOrderByRoundDescIdDesc(id)
                    .filter(previous -> previous.getReviewerUsername() != null
                            && previous.getReviewerUsername().equalsIgnoreCase(currentUser.getUsername()))
                    .ifPresent(previous -> {
                        throw new BizException("双人复核要求：同一用户不能连续复核同一条录音（上一复核人："
                                + previous.getReviewerUsername() + "）", HttpStatus.CONFLICT);
                    });
        }

        String comment = request.comment();
        ReviewRecord record = new ReviewRecord();
        record.setRecordingId(id);
        record.setReviewerId(currentUser.getId());
        record.setReviewerUsername(currentUser.getUsername());
        record.setResult(result);
        record.setViolationType(result == ReviewResult.CONFIRMED_VIOLATION ? type.name() : null);
        record.setComment(comment);
        record.setRound(round);
        reviewRecordRepository.save(record);

        recording.setReviewCount(round);
        boolean finalized = round >= requiredApprovals;
        if (finalized) {
            finalizeRecording(recording, result, type, comment, currentUser.getUsername());
        } else {
            // 未达标：保持待复检，不回馈语料、不触发词挖掘，也不写最终结论字段
            recording.setStatus(RecordingStatus.NEEDS_REVIEW);
            logStage(recording.getId(), "REVIEW", "INFO",
                    "复核提交（" + round + "/" + requiredApprovals + "），待其他复核人确认");
        }
        Recording saved = recordingRepository.save(recording);

        auditService.record(AuditAction.REVIEW_SUBMIT, "RECORDING", id,
                "第 " + round + "/" + requiredApprovals + " 轮复核，结论 " + result
                        + (finalized ? "，已落最终结论" : "，待其他复核人"));
        log.info("录音 {} 复核提交：round={}/{}, result={}, reviewer={}, finalized={}",
                id, round, requiredApprovals, result, currentUser.getUsername(), finalized);
        return saved;
    }

    /** 复核历史（登录即可查看），按轮次正序 */
    public List<ReviewRecord> history(Long recordingId) {
        recordingRepository.findById(recordingId)
                .orElseThrow(() -> new BizException("录音不存在: " + recordingId, HttpStatus.NOT_FOUND));
        return reviewRecordRepository.findByRecordingIdOrderByRoundAscIdAsc(recordingId);
    }

    /** 达到所需复核人数后落最终结论：状态、违规类型、语料回馈；词挖掘在事务提交后异步触发 */
    private void finalizeRecording(Recording recording, ReviewResult result, ViolationType type,
                                   String comment, String reviewer) {
        if (result == ReviewResult.CONFIRMED_VIOLATION) {
            recording.setStatus(RecordingStatus.VIOLATION_CONFIRMED);
            recording.setViolationType(type.name());
            recording.setViolationTypeLabel(type.getLabel());
            // 结论回馈语料库（违规样本）
            corpusService.append(recording, CorpusLabel.VIOLATION, type.name(),
                    StringUtils.hasText(comment) ? comment : extractAiReason(recording));
            logStage(recording.getId(), "REVIEW", "WARN",
                    "人工复检确认违规（" + type.getLabel() + "）：" + (StringUtils.hasText(comment) ? comment : "无备注"));
            // 外部告警（Webhook）同样放在事务提交后：避免「通知已发但事务回滚」的假告警
            notifyConfirmedViolationAfterCommit(recording, type, comment);
            // 从确认违规录音挖掘新词典词（停用待审核）：AI 调用可能耗时数百秒，
            // 移到事务提交后异步执行，异常只记日志，不阻塞/回滚复检
            triggerMiningAfterCommit(recording, type);
        } else {
            recording.setStatus(RecordingStatus.FALSE_POSITIVE);
            // 结论回馈语料库（合规样本）
            corpusService.append(recording, CorpusLabel.COMPLIANT, null,
                    StringUtils.hasText(comment) ? comment : extractAiReason(recording));
            logStage(recording.getId(), "REVIEW", "INFO",
                    "人工复检判定为误报：" + (StringUtils.hasText(comment) ? comment : "无备注"));
        }

        recording.setReviewResult(result);
        recording.setReviewComment(comment);
        recording.setReviewer(reviewer);
        recording.setReviewTime(LocalDateTime.now());
    }

    /**
     * 事务提交后发送「复核确认违规」Webhook 告警。
     *
     * <p>NotificationService 的发送本身是 @Async 且吞异常，这里只负责「提交后才发」的时序，
     * 保证不会出现事务回滚但告警已发出的情况。</p>
     */
    private void notifyConfirmedViolationAfterCommit(Recording recording, ViolationType type, String comment) {
        Runnable send = () -> notificationService.notify(NotifyEvent.REVIEW_CONFIRMED_VIOLATION,
                recording.getId(), "复核确认违规（" + type.getLabel() + "）",
                "录音 #" + recording.getId() + "（" + recording.getFileName() + "）经人工复核确认违规："
                        + type.getLabel() + (StringUtils.hasText(comment) ? "，备注：" + comment : ""),
                null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /**
     * 事务提交后异步触发词挖掘。
     *
     * <p>有事务上下文时注册 {@code afterCommit} 回调，保证落库成功后才调用 AI；
     * 无事务上下文（单测或未来非事务调用路径）直接触发，真实 Bean 仍经 {@code @Async} 代理异步执行。</p>
     */
    private void triggerMiningAfterCommit(Recording recording, ViolationType type) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    miningTrigger.mine(recording, type);
                }
            });
        } else {
            miningTrigger.mine(recording, type);
        }
    }

    private AppUserDetails currentUserOrThrow() {
        return SecurityUtils.currentUser()
                .orElseThrow(() -> new BizException("未登录或登录已过期", HttpStatus.UNAUTHORIZED));
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
