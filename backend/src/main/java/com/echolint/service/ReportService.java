package com.echolint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echolint.domain.AuditAction;
import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.dto.RecordingFilter;
import com.echolint.entity.Recording;
import com.echolint.entity.ReviewRecord;
import com.echolint.repository.RecordingRepository;
import com.echolint.repository.ReviewRecordRepository;
import com.echolint.util.CsvWriter;
import com.echolint.util.TimeRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 报表导出（CSV，UTF-8 BOM）：录音清单 / 违规明细 / 复核记录。
 *
 * <p>导出上限 {@value #MAX_EXPORT_ROWS} 行；录音与复核均「一次查询 + 内存流式拼装」，
 * 复核记录关联的录音元数据用 {@code findAllById} 批量回查，避免 N+1。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 单次导出最大行数（超出部分截断） */
    public static final int MAX_EXPORT_ROWS = 10000;

    private static final String[] RECORDING_HEADER = {
            "id", "fileName", "agentName", "teamName", "callTime", "direction", "businessLine",
            "status", "sampled", "hitCount", "violationType", "violationTypeLabel",
            "aiViolation", "aiConfidence", "reviewer", "reviewTime", "uploadTime",
            "durationSeconds", "errorMessage"};

    private static final String[] VIOLATION_HEADER = {
            "id", "fileName", "agentName", "teamName", "callTime", "direction", "businessLine",
            "violationType", "violationTypeLabel", "reviewer", "reviewTime", "reviewComment",
            "reviewCount", "aiViolation", "aiConfidence", "hitCount", "uploadTime"};

    private static final String[] REVIEW_HEADER = {
            "reviewId", "recordingId", "fileName", "agentName", "teamName", "callTime", "direction",
            "businessLine", "round", "reviewer", "result", "violationType", "comment", "createdAt"};

    private final RecordingRepository recordingRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    /** 录音清单：与列表相同的过滤条件 */
    public byte[] recordingsCsv(RecordingFilter filter) {
        List<Recording> rows = loadRecordings(filter);
        List<String[]> lines = new ArrayList<>(rows.size() + 1);
        lines.add(RECORDING_HEADER.clone());
        for (Recording r : rows) {
            AiInfo ai = aiInfo(r);
            lines.add(new String[]{
                    str(r.getId()),
                    r.getFileName(),
                    r.getAgentName(),
                    r.getTeamName(),
                    str(r.getCallTime()),
                    directionOf(r).name(),
                    r.getBusinessLine(),
                    str(r.getStatus()),
                    str(r.isSampled()),
                    str(r.getHitCount() == null ? 0 : r.getHitCount()),
                    r.getViolationType(),
                    r.getViolationTypeLabel(),
                    str(ai.violation()),
                    str(ai.confidence()),
                    r.getReviewer(),
                    str(r.getReviewTime()),
                    str(r.getUploadTime()),
                    str(r.getDurationSeconds()),
                    r.getErrorMessage()});
        }
        auditExport("recordings.csv", rows.size(), filter);
        return CsvWriter.toBytes(lines);
    }

    /** 违规明细：仅人工确认违规（VIOLATION_CONFIRMED），附复核人/备注等复核信息 */
    public byte[] violationsCsv(RecordingFilter filter) {
        RecordingFilter forced = violationsFilter(filter);
        List<Recording> rows = loadRecordings(forced);
        List<String[]> lines = new ArrayList<>(rows.size() + 1);
        lines.add(VIOLATION_HEADER.clone());
        for (Recording r : rows) {
            AiInfo ai = aiInfo(r);
            lines.add(new String[]{
                    str(r.getId()),
                    r.getFileName(),
                    r.getAgentName(),
                    r.getTeamName(),
                    str(r.getCallTime()),
                    directionOf(r).name(),
                    r.getBusinessLine(),
                    r.getViolationType(),
                    r.getViolationTypeLabel(),
                    r.getReviewer(),
                    str(r.getReviewTime()),
                    r.getReviewComment(),
                    str(r.getReviewCount()),
                    str(ai.violation()),
                    str(ai.confidence()),
                    str(r.getHitCount() == null ? 0 : r.getHitCount()),
                    str(r.getUploadTime())});
        }
        auditExport("violations.csv", rows.size(), forced);
        return CsvWriter.toBytes(lines);
    }

    /** 复核记录：review_records 关联录音元数据，按复核时间倒序 */
    public byte[] reviewsCsv(TimeRanges.Range range) {
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Specification<ReviewRecord> spec = (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), range.from()),
                cb.lessThan(root.get("createdAt"), range.toExclusive()));
        List<ReviewRecord> reviews = reviewRecordRepository.findAll(spec, pageable).getContent();

        Map<Long, Recording> recordings = new HashMap<>();
        List<Long> recordingIds = reviews.stream()
                .map(ReviewRecord::getRecordingId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!recordingIds.isEmpty()) {
            recordingRepository.findAllById(recordingIds).forEach(r -> recordings.put(r.getId(), r));
        }

        List<String[]> lines = new ArrayList<>(reviews.size() + 1);
        lines.add(REVIEW_HEADER.clone());
        for (ReviewRecord review : reviews) {
            Recording r = recordings.get(review.getRecordingId());
            lines.add(new String[]{
                    str(review.getId()),
                    str(review.getRecordingId()),
                    r == null ? "" : r.getFileName(),
                    r == null ? "" : r.getAgentName(),
                    r == null ? "" : r.getTeamName(),
                    r == null || r.getCallTime() == null ? "" : str(r.getCallTime()),
                    r == null ? CallDirection.UNKNOWN.name() : directionOf(r).name(),
                    r == null ? "" : r.getBusinessLine(),
                    str(review.getRound()),
                    review.getReviewerUsername(),
                    str(review.getResult()),
                    review.getViolationType(),
                    review.getComment(),
                    str(review.getCreatedAt())});
        }
        auditService.record(AuditAction.REPORT_EXPORT, "REPORT", "reviews.csv",
                "导出报表 reviews.csv，" + reviews.size() + " 行"
                        + "（" + range.from() + " ~ " + range.toExclusive() + "）");
        return CsvWriter.toBytes(lines);
    }

    private List<Recording> loadRecordings(RecordingFilter filter) {
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_ROWS,
                Sort.by(Sort.Direction.DESC, "id"));
        return recordingRepository.findAll(RecordingSpecifications.of(filter), pageable).getContent();
    }

    /** 违规明细固定只导人工确认违规，其余筛选条件保留 */
    RecordingFilter violationsFilter(RecordingFilter filter) {
        return filter.withStatuses(List.of(RecordingStatus.VIOLATION_CONFIRMED));
    }

    private void auditExport(String report, int rows, RecordingFilter filter) {
        auditService.record(AuditAction.REPORT_EXPORT, "REPORT", report,
                "导出报表 " + report + "，" + rows + " 行" + filterSummary(filter));
    }

    private String filterSummary(RecordingFilter filter) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "状态", filter.statuses() == null ? null : filter.statuses().toString());
        appendIf(sb, "关键字", filter.keyword());
        appendIf(sb, "指派", filter.assignedTo());
        appendIf(sb, "优先级", filter.priority() == null ? null : filter.priority().name());
        appendIf(sb, "坐席", filter.agentName());
        appendIf(sb, "团队", filter.teamName());
        appendIf(sb, "方向", filter.direction() == null ? null : filter.direction().name());
        if (filter.from() != null && !TimeRanges.MIN.equals(filter.from())) {
            appendIf(sb, "从", filter.from().toString());
        }
        if (filter.toExclusive() != null && !TimeRanges.MAX.equals(filter.toExclusive())) {
            appendIf(sb, "至", filter.toExclusive().toString());
        }
        return sb.length() == 0 ? "" : "（" + sb.substring(1) + "）";
    }

    private static void appendIf(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(' ').append(label).append('=').append(value);
        }
    }

    private static CallDirection directionOf(Recording recording) {
        return recording.getDirection() == null ? CallDirection.UNKNOWN : recording.getDirection();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** AI 复筛结论（供导出列使用；JSON 异常按无结果处理） */
    private AiInfo aiInfo(Recording recording) {
        String json = recording.getAiResultJson();
        if (json == null || json.isBlank()) {
            return AiInfo.EMPTY;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Boolean violation = node.path("violation").isBoolean() ? node.path("violation").asBoolean() : null;
            Double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble() : null;
            return new AiInfo(violation, confidence);
        } catch (Exception e) {
            return AiInfo.EMPTY;
        }
    }

    private record AiInfo(Boolean violation, Double confidence) {
        private static final AiInfo EMPTY = new AiInfo(null, null);
    }
}
