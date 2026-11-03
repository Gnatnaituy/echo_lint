package com.echolint.service;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * 报表导出（CSV，UTF-8 BOM）：录音清单 / 违规明细 / 复核记录。
 *
 * <p>导出上限 {@value #MAX_EXPORT_ROWS} 行。录音与复核均按 {@value #EXPORT_BATCH_SIZE} 条一批 分页加载，逐批转换成 CSV
 * 行后立即释放实体，避免一次把整表实体（录音含 transcript/segmentsJson/dfaHitsJson/aiResultJson 四个 TEXT 大字段）读入内存；
 * 复核记录关联的录音元数据按 200 个 id 分块回查，且只保留导出需要的 6 个字段。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 单次导出最大行数（超出部分截断） */
    public static final int MAX_EXPORT_ROWS = 10000;

    /** 分批导出的批大小：每批 200 条，生成 CSV 行后释放实体 */
    static final int EXPORT_BATCH_SIZE = 200;

    private static final String[] RECORDING_HEADER = {
        "id",
        "fileName",
        "agentName",
        "teamName",
        "callTime",
        "direction",
        "businessLine",
        "status",
        "sampled",
        "hitCount",
        "violationType",
        "violationTypeLabel",
        "aiViolation",
        "aiConfidence",
        "reviewer",
        "reviewTime",
        "uploadTime",
        "durationSeconds",
        "errorMessage"
    };

    private static final String[] VIOLATION_HEADER = {
        "id",
        "fileName",
        "agentName",
        "teamName",
        "callTime",
        "direction",
        "businessLine",
        "violationType",
        "violationTypeLabel",
        "reviewer",
        "reviewTime",
        "reviewComment",
        "reviewCount",
        "aiViolation",
        "aiConfidence",
        "hitCount",
        "uploadTime"
    };

    private static final String[] REVIEW_HEADER = {
        "reviewId", "recordingId", "fileName", "agentName", "teamName", "callTime", "direction",
        "businessLine", "round", "reviewer", "result", "violationType", "comment", "createdAt"
    };

    private final RecordingRepository recordingRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    /** 录音清单：与列表相同的过滤条件 */
    public byte[] recordingsCsv(RecordingFilter filter) {
        List<String[]> lines = new ArrayList<>();
        lines.add(RECORDING_HEADER.clone());
        int rows =
                collectRecordingRows(
                        filter,
                        lines,
                        r -> {
                            AiInfo ai = aiInfo(r);
                            return new String[] {
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
                                r.getErrorMessage()
                            };
                        });
        auditExport("recordings.csv", rows, filter);
        return CsvWriter.toBytes(lines);
    }

    /** 违规明细：仅人工确认违规（VIOLATION_CONFIRMED），附复核人/备注等复核信息 */
    public byte[] violationsCsv(RecordingFilter filter) {
        RecordingFilter forced = violationsFilter(filter);
        List<String[]> lines = new ArrayList<>();
        lines.add(VIOLATION_HEADER.clone());
        int rows =
                collectRecordingRows(
                        forced,
                        lines,
                        r -> {
                            AiInfo ai = aiInfo(r);
                            return new String[] {
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
                                str(r.getUploadTime())
                            };
                        });
        auditExport("violations.csv", rows, forced);
        return CsvWriter.toBytes(lines);
    }

    /** 复核记录：review_records 关联录音元数据，按复核时间倒序 */
    public byte[] reviewsCsv(TimeRanges.Range range) {
        Specification<ReviewRecord> spec =
                (root, query, cb) ->
                        cb.and(
                                cb.greaterThanOrEqualTo(root.get("createdAt"), range.from()),
                                cb.lessThan(root.get("createdAt"), range.toExclusive()));
        List<String[]> lines = new ArrayList<>();
        lines.add(REVIEW_HEADER.clone());
        int rows = 0;
        int page = 0;
        while (rows < MAX_EXPORT_ROWS) {
            List<ReviewRecord> batch =
                    reviewRecordRepository
                            .findAll(
                                    spec,
                                    PageRequest.of(
                                            page,
                                            EXPORT_BATCH_SIZE,
                                            Sort.by(Sort.Direction.DESC, "id")))
                            .getContent();
            if (batch.isEmpty()) {
                break;
            }
            Map<Long, RecordingMeta> recordings = loadRecordingMeta(batch);
            for (ReviewRecord review : batch) {
                if (rows >= MAX_EXPORT_ROWS) {
                    break;
                }
                RecordingMeta r = recordings.get(review.getRecordingId());
                lines.add(
                        new String[] {
                            str(review.getId()),
                            str(review.getRecordingId()),
                            r == null ? "" : r.fileName(),
                            r == null ? "" : r.agentName(),
                            r == null ? "" : r.teamName(),
                            r == null || r.callTime() == null ? "" : str(r.callTime()),
                            r == null ? CallDirection.UNKNOWN.name() : r.direction().name(),
                            r == null ? "" : r.businessLine(),
                            str(review.getRound()),
                            review.getReviewerUsername(),
                            str(review.getResult()),
                            review.getViolationType(),
                            review.getComment(),
                            str(review.getCreatedAt())
                        });
                rows++;
            }
            if (rows >= MAX_EXPORT_ROWS || batch.size() < EXPORT_BATCH_SIZE) {
                break;
            }
            page++;
        }
        auditService.record(
                AuditAction.REPORT_EXPORT,
                "REPORT",
                "reviews.csv",
                "导出报表 reviews.csv，"
                        + rows
                        + " 行"
                        + "（"
                        + range.from()
                        + " ~ "
                        + range.toExclusive()
                        + "）");
        return CsvWriter.toBytes(lines);
    }

    /**
     * 分批加载录音并逐批转换成 CSV 行，返回实际导出行数（最多 {@value #MAX_EXPORT_ROWS}）。
     *
     * <p>每批最多 {@value #EXPORT_BATCH_SIZE} 条，转换完即丢弃本批实体（只留下 CSV 字符串）， 避免全量实体长时间驻留堆内存；查询形态与列表接口一致（id
     * 倒序），上限行为不变。
     */
    private int collectRecordingRows(
            RecordingFilter filter, List<String[]> lines, Function<Recording, String[]> toRow) {
        Specification<Recording> spec = RecordingSpecifications.of(filter);
        int rows = 0;
        int page = 0;
        while (rows < MAX_EXPORT_ROWS) {
            List<Recording> batch =
                    recordingRepository
                            .findAll(
                                    spec,
                                    PageRequest.of(
                                            page,
                                            EXPORT_BATCH_SIZE,
                                            Sort.by(Sort.Direction.DESC, "id")))
                            .getContent();
            for (Recording recording : batch) {
                if (rows >= MAX_EXPORT_ROWS) {
                    break;
                }
                lines.add(toRow.apply(recording));
                rows++;
            }
            if (rows >= MAX_EXPORT_ROWS || batch.size() < EXPORT_BATCH_SIZE) {
                break;
            }
            page++;
        }
        return rows;
    }

    /** 分批回查录音元数据（每 {@value #EXPORT_BATCH_SIZE} 个 id 一次），只保留导出列后丢弃实体 */
    private Map<Long, RecordingMeta> loadRecordingMeta(List<ReviewRecord> reviews) {
        List<Long> recordingIds =
                reviews.stream()
                        .map(ReviewRecord::getRecordingId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, RecordingMeta> recordings = new HashMap<>();
        for (int start = 0; start < recordingIds.size(); start += EXPORT_BATCH_SIZE) {
            int end = Math.min(start + EXPORT_BATCH_SIZE, recordingIds.size());
            recordingRepository
                    .findAllById(recordingIds.subList(start, end))
                    .forEach(r -> recordings.put(r.getId(), RecordingMeta.of(r)));
        }
        return recordings;
    }

    /** 违规明细固定只导人工确认违规，其余筛选条件保留 */
    RecordingFilter violationsFilter(RecordingFilter filter) {
        return filter.withStatuses(List.of(RecordingStatus.VIOLATION_CONFIRMED));
    }

    private void auditExport(String report, int rows, RecordingFilter filter) {
        auditService.record(
                AuditAction.REPORT_EXPORT,
                "REPORT",
                report,
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
            Boolean violation =
                    node.path("violation").isBoolean() ? node.path("violation").asBoolean() : null;
            Double confidence =
                    node.path("confidence").isNumber() ? node.path("confidence").asDouble() : null;
            return new AiInfo(violation, confidence);
        } catch (Exception e) {
            return AiInfo.EMPTY;
        }
    }

    private record AiInfo(Boolean violation, Double confidence) {
        private static final AiInfo EMPTY = new AiInfo(null, null);
    }

    /** 复核记录导出的录音元数据快照（只保留 6 个导出列，避免整实体驻留内存） */
    private record RecordingMeta(
            String fileName,
            String agentName,
            String teamName,
            LocalDateTime callTime,
            CallDirection direction,
            String businessLine) {
        private static RecordingMeta of(Recording recording) {
            return new RecordingMeta(
                    recording.getFileName(),
                    recording.getAgentName(),
                    recording.getTeamName(),
                    recording.getCallTime(),
                    directionOf(recording),
                    recording.getBusinessLine());
        }
    }
}
