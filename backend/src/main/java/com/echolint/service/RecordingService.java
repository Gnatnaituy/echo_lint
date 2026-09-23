package com.echolint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.RecordingStatus;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.exception.BizException;
import com.echolint.repository.PipelineLogRepository;
import com.echolint.repository.RecordingRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 录音管理：上传、查询、详情、删除、重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private final RecordingRepository recordingRepository;
    private final PipelineLogRepository pipelineLogRepository;
    private final PipelineService pipelineService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * 上传录音：落盘 -> 建记录(PENDING) -> 异步启动稽核流水线
     */
    public Recording upload(MultipartFile file) {
        validateFile(file);

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
        Recording saved = recordingRepository.saveAndFlush(recording);

        log.info("录音 {} 上传成功（{}，{} 字节），启动稽核流水线", saved.getId(), original, file.getSize());
        pipelineService.run(saved.getId());
        return saved;
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
            throw new BizException("文件大小超过限制（" + (appProperties.getMaxSizeBytes() / 1024 / 1024) + "MB），Whisper 单文件上限 25MB");
        }
    }

    private String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    public Page<Recording> query(List<RecordingStatus> statuses, String keyword, Pageable pageable) {
        Specification<Recording> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) {
                ps.add(root.get("status").in(statuses));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("transcript")), kw),
                        cb.like(cb.lower(root.get("fileName")), kw)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return recordingRepository.findAll(spec, pageable);
    }

    public Recording get(Long id) {
        return recordingRepository.findById(id)
                .orElseThrow(() -> new BizException("录音不存在: " + id, HttpStatus.NOT_FOUND));
    }

    public List<PipelineLog> logs(Long id) {
        return pipelineLogRepository.findByRecordingIdOrderByCreatedAtAscIdAsc(id);
    }

    @Transactional
    public void delete(Long id) {
        Recording recording = get(id);
        pipelineLogRepository.deleteAll(pipelineLogRepository.findByRecordingIdOrderByCreatedAtAscIdAsc(id));
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

        pipelineService.run(recording.getId());
        log.info("录音 {} 手动触发重新处理（原状态 {}）", id, status);
        return recording;
    }
}