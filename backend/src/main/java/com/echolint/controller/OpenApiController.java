package com.echolint.controller;

import com.echolint.dto.OpenRecordingView;
import com.echolint.dto.OpenUploadResponse;
import com.echolint.dto.RecordingMetadata;
import com.echolint.entity.Recording;
import com.echolint.security.ApiKeyAuthFilter;
import com.echolint.service.ApiKeyService;
import com.echolint.service.RecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 开放 API（Batch 4a）：供外部系统用 {@code X-API-Key} 上传录音并查询处理状态。
 *
 * <p>访问控制由 {@link ApiKeyAuthFilter} 完成（SecurityConfig 对 {@code /api/open/**} permitAll）。
 * 上传复用内部 {@link RecordingService} 的校验/落盘/去重/入队逻辑，审计动作单独记
 * {@code OPEN_API_UPLOAD}（actor = api-key:名称）。</p>
 */
@RestController
@RequestMapping("/api/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final RecordingService recordingService;

    /**
     * 上传录音：multipart {@code file} + 可选元数据，202 表示已接收并入队（处理异步进行）。
     */
    @PostMapping("/recordings/upload")
    public ResponseEntity<OpenUploadResponse> upload(
            @RequestAttribute(name = ApiKeyAuthFilter.ATTR_CONTEXT, required = false)
            ApiKeyService.ApiKeyContext apiKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String teamName,
            @RequestParam(required = false) String callTime,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String businessLine) {
        RecordingMetadata metadata = RecordingMetadata.of(agentName, teamName, callTime, direction, businessLine);
        String keyName = apiKey == null ? "unknown" : apiKey.name();
        Recording saved = recordingService.uploadFromOpenApi(file, metadata, keyName);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new OpenUploadResponse(
                saved.getId(), saved.getStatus().name(), saved.getFileName()));
    }

    /** 查询处理状态（限定字段，不返回转写/复核意见等内部内容） */
    @GetMapping("/recordings/{id}")
    public OpenRecordingView get(@PathVariable Long id) {
        Recording recording = recordingService.get(id);
        return new OpenRecordingView(
                recording.getId(),
                recording.getStatus().name(),
                recording.getFileName(),
                recording.getUploadTime(),
                recording.getProcessedTime(),
                recording.getDurationSeconds(),
                recording.getHitCount(),
                recording.getViolationType(),
                recording.getReviewResult() == null ? null : recording.getReviewResult().name(),
                recording.getErrorMessage());
    }
}
