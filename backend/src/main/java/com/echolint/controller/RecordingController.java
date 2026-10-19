package com.echolint.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.config.AppProperties;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.dto.AssignRecordingRequest;
import com.echolint.dto.RecordingSummary;
import com.echolint.entity.AiInvocation;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.entity.ReviewRecord;
import com.echolint.exception.BizException;
import com.echolint.service.RecordingService;
import com.echolint.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 录音上传、查询、复核队列操作（认领/指派/重开）。
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    /** 列表分页大小硬上限，防止一次拉全表 */
    private static final int MAX_PAGE_SIZE = 100;

    private final RecordingService recordingService;
    private final ReviewService reviewService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @PostMapping("/upload")
    public ResponseEntity<RecordingSummary> upload(@RequestParam("file") MultipartFile file) {
        Recording saved = recordingService.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(summary(saved));
    }

    @GetMapping
    public Page<RecordingSummary> list(@RequestParam(required = false) String statuses,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String assignedTo,
                                       @RequestParam(required = false) String priority,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        List<RecordingStatus> statusList = null;
        if (statuses != null && !statuses.isBlank()) {
            statusList = Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(RecordingStatus::valueOf)
                    .toList();
        }
        ReviewPriority priorityFilter = parsePriority(priority);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));
        return recordingService.query(statusList, keyword, assignedTo, priorityFilter, pageable)
                .map(this::summary);
    }

    @GetMapping("/{id}")
    public Recording detail(@PathVariable Long id) {
        return recordingService.get(id);
    }

    @GetMapping("/{id}/logs")
    public List<PipelineLog> logs(@PathVariable Long id) {
        return recordingService.logs(id);
    }

    /** AI 调用留痕：完整 prompt 与模型原始回复，用于回溯「AI 为什么这么判」 */
    @GetMapping("/{id}/ai-invocations")
    public List<AiInvocation> aiInvocations(@PathVariable Long id) {
        return recordingService.aiInvocations(id);
    }

    /** 复核历史（登录即可查看），按轮次正序 */
    @GetMapping("/{id}/reviews")
    public List<ReviewRecord> reviews(@PathVariable Long id) {
        return reviewService.history(id);
    }

    @PostMapping("/{id}/retry")
    public RecordingSummary retry(@PathVariable Long id) {
        return summary(recordingService.retry(id));
    }

    /** 认领复核（ADMIN + REVIEWER） */
    @PostMapping("/{id}/claim")
    public RecordingSummary claim(@PathVariable Long id) {
        return summary(recordingService.claim(id));
    }

    /** 管理员指派复核人（仅 ADMIN，URL 规则见 SecurityConfig） */
    @PutMapping("/{id}/assign")
    public RecordingSummary assign(@PathVariable Long id, @Valid @RequestBody AssignRecordingRequest request) {
        return summary(recordingService.assign(id, request.username()));
    }

    /** 重开复核（ADMIN）：终态 -> NEEDS_REVIEW */
    @PostMapping("/{id}/reopen")
    public RecordingSummary reopen(@PathVariable Long id) {
        return summary(recordingService.reopen(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        recordingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 兼容文档中的 POST /api/recordings/{id}/delete 写法 */
    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> deleteByPost(@PathVariable Long id) {
        recordingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private RecordingSummary summary(Recording recording) {
        return RecordingSummary.from(recording, objectMapper, appProperties.reviewRequiredApprovals());
    }

    private ReviewPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        try {
            return ReviewPriority.valueOf(priority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException("未知优先级: " + priority + "（仅支持 LOW / NORMAL / HIGH）");
        }
    }
}
