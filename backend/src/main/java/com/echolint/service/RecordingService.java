package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.UserRole;
import com.echolint.dto.RecordingFilter;
import com.echolint.dto.RecordingMetadata;
import com.echolint.entity.AiInvocation;
import com.echolint.entity.AppUser;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.AppUserRepository;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 录音管理：上传、查询、详情、删除、重试、认领、指派、重开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final AiInvocationService aiInvocationService;
    private final PipelineService pipelineService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AppUserRepository userRepository;
    private final ReviewRecordRepository reviewRecordRepository;

    /**
     * 上传录音：落盘 -> 建记录(PENDING，附可选元数据) -> 异步启动稽核流水线
     */
    public Recording upload(MultipartFile file, RecordingMetadata metadata) {
        validateFile(file);
        RecordingMetadata meta = metadata == null ? RecordingMetadata.EMPTY : metadata;

        String original = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "recording"));
        String ext = extensionOf(original);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            Path dir = appProperties.absoluteUploadDir();
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(storedName).toFile());
        } catch (IOException e) {
            throw new BizException("录音保存失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Recording recording = new Recording();
        recording.setFileName(original);
        recording.setFilePath(storedName);
        recording.setFileSize(file.getSize());
        recording.setMimeType(file.getContentType());
        recording.setStatus(RecordingStatus.PENDING);
        applyMetadata(recording, meta);
        Recording saved = recordingRepository.saveAndFlush(recording);

        auditService.record(AuditAction.RECORDING_UPLOAD, "RECORDING", saved.getId(),
                "上传录音 " + original + "（" + file.getSize() + " 字节）"
                        + metadataSummary(meta));
        log.info("录音 {} 上传成功（{}，{} 字节），启动稽核流水线", saved.getId(), original, file.getSize());
        pipelineService.run(saved.getId());
        return saved;
    }

    private void applyMetadata(Recording recording, RecordingMetadata metadata) {
        recording.setAgentName(metadata.agentName());
        recording.setTeamName(metadata.teamName());
        recording.setCallTime(metadata.callTime());
        recording.setDirection(metadata.direction());
        recording.setBusinessLine(metadata.businessLine());
    }

    /** 审计详情里的元数据摘要（未填写的不出现） */
    private String metadataSummary(RecordingMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        if (metadata.agentName() != null) {
            sb.append("，坐席 ").append(metadata.agentName());
        }
        if (metadata.teamName() != null) {
            sb.append("，团队 ").append(metadata.teamName());
        }
        if (metadata.callTime() != null) {
            sb.append("，通话时间 ").append(metadata.callTime());
        }
        if (metadata.direction() != null && metadata.direction() != com.echolint.domain.CallDirection.UNKNOWN) {
            sb.append("，方向 ").append(metadata.direction().getLabel());
        }
        if (metadata.businessLine() != null) {
            sb.append("，业务线 ").append(metadata.businessLine());
        }
        return sb.toString();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        String ext = extensionOf(StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "")));
        if (!appProperties.getAllowedExtensions().contains(ext)) {
            throw new BizException("不支持的文件类型 ." + ext + "，支持: " + String.join(", ", appProperties.getAllowedExtensions()));
        }
        if (file.getSize() > appProperties.getMaxSizeBytes()) {
            throw new BizException("文件大小超过限制（" + (appProperties.getMaxSizeBytes() / 1024 / 1024) + "MB）");
        }
    }

    private String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 录音列表：状态 / 关键字 / 指派对象 / 优先级 / 坐席 / 团队 / 方向 / 时间范围 过滤。
     */
    public Page<Recording> query(RecordingFilter filter, Pageable pageable) {
        return recordingRepository.findAll(RecordingSpecifications.of(filter), pageable);
    }

    public Recording get(Long id) {
        return recordingRepository.findById(id)
                .orElseThrow(() -> new BizException("录音不存在: " + id, HttpStatus.NOT_FOUND));
    }

    public List<PipelineLog> logs(Long id) {
        return pipelineLogRepository.findByRecordingIdOrderByCreatedAtAscIdAsc(id);
    }

    /** AI 调用留痕（完整请求 + 原始回复） */
    public List<AiInvocation> aiInvocations(Long id) {
        get(id); // 录音不存在时直接 404
        return aiInvocationService.listByRecording(id);
    }

    @Transactional
    public void delete(Long id) {
        Recording recording = get(id);
        pipelineLogRepository.deleteAll(pipelineLogRepository.findByRecordingIdOrderByCreatedAtAscIdAsc(id));
        aiInvocationService.deleteByRecording(id);
        reviewRecordRepository.deleteByRecordingId(id);
        recordingRepository.delete(recording);
        deleteFile(recording.getFilePath());
        // 双声道分轨文件一并清理
        if (StringUtils.hasText(recording.getChannelFilesJson())) {
            try {
                var node = objectMapper.readTree(recording.getChannelFilesJson());
                node.forEach(channelFile -> deleteFile(channelFile.asText()));
            } catch (Exception e) {
                log.warn("解析分轨文件失败: {}", e.getMessage());
            }
        }
        auditService.record(AuditAction.RECORDING_DELETE, "RECORDING", id,
                "删除录音 " + recording.getFileName());
        log.info("录音 {} 已删除", id);
    }

    private void deleteFile(String storedFileName) {
        if (!StringUtils.hasText(storedFileName)) {
            return;
        }
        try {
            Files.deleteIfExists(appProperties.absoluteUploadDir().resolve(storedFileName));
        } catch (IOException e) {
            log.warn("删除文件失败 {}: {}", storedFileName, e.getMessage());
        }
    }

    /**
     * 手动推进/重新处理：清空上一轮处理产物后重新进入流水线。
     *
     * 允许的状态：
     * - PENDING：排队但未开始（例如上传后服务重启，流水线丢失）
     * - FAILED：上次处理失败
     * - TRANSCRIBING / DFA_CHECKING / AI_CHECKING：卡住的中间态（服务重启中断）
     * 不允许：COMPLIANT / NEEDS_REVIEW / VIOLATION_CONFIRMED / FALSE_POSITIVE，
     * 这些已有结论，重新跑会覆盖结果（待复检请走「人工复检」）。
     */
    public Recording retry(Long id) {
        Recording recording = get(id);
        RecordingStatus status = recording.getStatus();

        if (pipelineService.isInFlight(id)) {
            throw new BizException("该录音正在处理中，请稍候再试");
        }
        if (status != RecordingStatus.PENDING
                && status != RecordingStatus.FAILED
                && status != RecordingStatus.TRANSCRIBING
                && status != RecordingStatus.DFA_CHECKING
                && status != RecordingStatus.AI_CHECKING) {
            throw new BizException("当前状态（" + status + "）已有稽核结论，不支持重新处理；待复检录音请在「人工复检」中处理");
        }
        if (!Files.exists(appProperties.absoluteUploadDir().resolve(recording.getFilePath()))) {
            throw new BizException("原始录音文件不存在，无法重新处理");
        }

        // 清理上一轮产物，避免新旧结果混杂
        if (StringUtils.hasText(recording.getChannelFilesJson())) {
            try {
                objectMapper.readTree(recording.getChannelFilesJson())
                        .forEach(node -> deleteFile(node.asText()));
            } catch (Exception e) {
                log.warn("清理分轨文件失败: {}", e.getMessage());
            }
        }
        recording.setStatus(RecordingStatus.PENDING);
        recording.setErrorMessage(null);
        recording.setTranscript(null);
        recording.setSegmentsJson(null);
        recording.setChannelCount(null);
        recording.setChannelFilesJson(null);
        recording.setDfaHitsJson(null);
        recording.setHitCount(0);
        recording.setAiResultJson(null);
        recording.setProcessedTime(null);
        recordingRepository.saveAndFlush(recording);

        auditService.record(AuditAction.RECORDING_RETRY, "RECORDING", id,
                "重新处理录音（原状态 " + status + "）");
        pipelineService.run(recording.getId());
        log.info("录音 {} 手动触发重新处理（原状态 {}）", id, status);
        return recording;
    }

    /**
     * 认领复核：仅待复检录音；已被他人认领返回 409；重复认领自己幂等。
     */
    @Transactional
    public Recording claim(Long id) {
        Recording recording = get(id);
        String username = currentUsernameOrThrow();
        if (recording.getStatus() != RecordingStatus.NEEDS_REVIEW) {
            throw new BizException("仅待复检录音可认领（当前状态 " + recording.getStatus() + "）",
                    HttpStatus.CONFLICT);
        }
        if (StringUtils.hasText(recording.getAssignedTo())
                && !recording.getAssignedTo().equalsIgnoreCase(username)) {
            throw new BizException("该录音已被 " + recording.getAssignedTo() + " 认领",
                    HttpStatus.CONFLICT);
        }
        recording.setAssignedTo(username);
        Recording saved = recordingRepository.save(recording);
        auditService.record(AuditAction.RECORDING_CLAIM, "RECORDING", id, "认领复核");
        log.info("用户 {} 认领录音 {}", username, id);
        return saved;
    }

    /**
     * 管理员指派复核人：用户必须存在、启用且角色为 ADMIN / REVIEWER。
     */
    @Transactional
    public Recording assign(Long id, String username) {
        Recording recording = get(id);
        String target = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(target)) {
            throw new BizException("用户名不能为空");
        }
        AppUser user = userRepository.findByUsername(target)
                .orElseThrow(() -> new BizException("用户不存在: " + target));
        if (!user.isEnabled()) {
            throw new BizException("用户已被禁用，不能指派: " + target);
        }
        if (user.getRole() == UserRole.VIEWER) {
            throw new BizException("只能指派给管理员或复核员");
        }
        recording.setAssignedTo(user.getUsername());
        Recording saved = recordingRepository.save(recording);
        auditService.record(AuditAction.RECORDING_ASSIGN, "RECORDING", id,
                "指派给 " + user.getUsername());
        log.info("录音 {} 指派给 {}", id, user.getUsername());
        return saved;
    }

    /**
     * 重开复核（ADMIN）：仅终态录音可重开；清空最终结论与复核轮次，保留复核历史。
     */
    @Transactional
    public Recording reopen(Long id) {
        Recording recording = get(id);
        RecordingStatus status = recording.getStatus();
        if (status != RecordingStatus.VIOLATION_CONFIRMED
                && status != RecordingStatus.FALSE_POSITIVE
                && status != RecordingStatus.COMPLIANT) {
            throw new BizException("仅已出最终结论的录音可重开（当前状态 " + status + "）",
                    HttpStatus.CONFLICT);
        }
        recording.setStatus(RecordingStatus.NEEDS_REVIEW);
        recording.setReviewResult(null);
        recording.setViolationType(null);
        recording.setViolationTypeLabel(null);
        recording.setReviewer(null);
        recording.setReviewTime(null);
        recording.setReviewComment(null);
        recording.setReviewCount(0);
        Recording saved = recordingRepository.save(recording);

        saveLog(id, "REVIEW", "INFO", "管理员重开复核（原状态 " + status + "），复核历史保留");
        auditService.record(AuditAction.REVIEW_REOPEN, "RECORDING", id,
                "重开复核（原状态 " + status + "）");
        log.info("录音 {} 被重开复核（原状态 {}）", id, status);
        return saved;
    }

    private String currentUsernameOrThrow() {
        return SecurityUtils.currentUsername()
                .orElseThrow(() -> new BizException("未登录或登录已过期", HttpStatus.UNAUTHORIZED));
    }

    private void saveLog(Long recordingId, String stage, String level, String message) {
        PipelineLog entry = new PipelineLog();
        entry.setRecordingId(recordingId);
        entry.setStage(stage);
        entry.setLevel(level);
        entry.setMessage(message);
        pipelineLogRepository.save(entry);
    }
}
