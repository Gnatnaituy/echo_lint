package com.echolint.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.domain.RecordingStatus;
import com.echolint.dto.RecordingSummary;
import com.echolint.entity.PipelineLog;
import com.echolint.entity.Recording;
import com.echolint.service.RecordingService;
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

/**
 * 录音上传与查询
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingService recordingService;
    private final ObjectMapper objectMapper;

    @PostMapping("/upload")
    public ResponseEntity<RecordingSummary> upload(@RequestParam("file") MultipartFile file) {
        Recording saved = recordingService.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RecordingSummary.from(saved, objectMapper));
    }

    @GetMapping
    public Page<RecordingSummary> list(@RequestParam(required = false) String statuses,
                                       @RequestParam(required = false) String keyword,
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
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return recordingService.query(statusList, keyword, pageable)
                .map(r -> RecordingSummary.from(r, objectMapper));
    }

    @GetMapping("/{id}")
    public Recording detail(@PathVariable Long id) {
        return recordingService.get(id);
    }

    @GetMapping("/{id}/logs")
    public List<PipelineLog> logs(@PathVariable Long id) {
        return recordingService.logs(id);
    }

    @PostMapping("/{id}/retry")
    public RecordingSummary retry(@PathVariable Long id) {
        return RecordingSummary.from(recordingService.retry(id), objectMapper);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        recordingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
