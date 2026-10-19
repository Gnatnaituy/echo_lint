package com.echolint.controller;

import com.echolint.dto.RecordingFilter;
import com.echolint.service.ReportService;
import com.echolint.util.TimeRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 报表导出（CSV）。登录即可访问（含 VIEWER），每次导出写 REPORT_EXPORT 审计。
 *
 * <p>响应统一为 {@code text/csv;charset=UTF-8}，带 UTF-8 BOM，
 * {@code Content-Disposition: attachment; filename="<前缀>-<yyyyMMdd>.csv"}。</p>
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reportService;

    /** 录音清单导出：与列表相同的过滤条件 */
    @GetMapping("/recordings.csv")
    public ResponseEntity<byte[]> recordings(@RequestParam(required = false) String statuses,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String assignedTo,
                                             @RequestParam(required = false) String priority,
                                             @RequestParam(required = false) String agentName,
                                             @RequestParam(required = false) String teamName,
                                             @RequestParam(required = false) String direction,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        RecordingFilter filter = RecordingFilter.of(statuses, keyword, assignedTo, priority,
                agentName, teamName, direction, from, to);
        return csv("recordings", reportService.recordingsCsv(filter));
    }

    /** 违规明细导出：仅 VIOLATION_CONFIRMED */
    @GetMapping("/violations.csv")
    public ResponseEntity<byte[]> violations(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String agentName,
                                             @RequestParam(required = false) String teamName,
                                             @RequestParam(required = false) String direction,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        RecordingFilter filter = RecordingFilter.of(null, keyword, null, null,
                agentName, teamName, direction, from, to);
        return csv("violations", reportService.violationsCsv(filter));
    }

    /** 复核记录导出：复核时间范围（from/to 可空），关联录音元数据 */
    @GetMapping("/reviews.csv")
    public ResponseEntity<byte[]> reviews(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return csv("reviews", reportService.reviewsCsv(TimeRanges.parseRange(from, to)));
    }

    private ResponseEntity<byte[]> csv(String prefix, byte[] body) {
        String filename = prefix + "-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
