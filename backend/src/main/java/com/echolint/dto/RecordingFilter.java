package com.echolint.dto;

import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.exception.BizException;
import com.echolint.util.TimeRanges;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 录音列表 / 报表导出共用的过滤条件。
 *
 * <p>时间过滤语义：优先 {@code callTime}，为空回退 {@code uploadTime}（JPQL coalesce）；
 * {@code from} 含起点，{@code to} 含当天/当刻；未传时分别退化为 1970 / 2999 的边界值。</p>
 *
 * <p>{@code unassignedOnly}：只查未指派复核人的录音（复检队列「未指派」筛选）；
 * 与 {@code assignedTo} 互斥，前端只会传其一。</p>
 */
public record RecordingFilter(
        List<RecordingStatus> statuses,
        String keyword,
        String assignedTo,
        ReviewPriority priority,
        String agentName,
        String teamName,
        CallDirection direction,
        LocalDateTime from,
        LocalDateTime toExclusive,
        boolean unassignedOnly) {

    /**
     * 从请求参数解析过滤条件（非法枚举/时间格式返回 400）。
     */
    public static RecordingFilter of(String statuses, String keyword, String assignedTo, String priority,
                                     String agentName, String teamName, String direction,
                                     String from, String to) {
        return of(statuses, keyword, assignedTo, priority, agentName, teamName, direction, from, to, false);
    }

    /**
     * 带「未指派」条件的版本（复检队列用）：{@code unassignedOnly=true} 时忽略 {@code assignedTo}。
     */
    public static RecordingFilter of(String statuses, String keyword, String assignedTo, String priority,
                                     String agentName, String teamName, String direction,
                                     String from, String to, boolean unassignedOnly) {
        TimeRanges.Range range = TimeRanges.parseRange(from, to);
        return new RecordingFilter(
                parseStatuses(statuses),
                trimToNull(keyword),
                trimToNull(assignedTo),
                parsePriority(priority),
                trimToNull(agentName),
                trimToNull(teamName),
                CallDirection.parseFilter(direction),
                range.from(),
                range.toExclusive(),
                unassignedOnly);
    }

    /** 仅按时间范围过滤（报表/统计等简单场景） */
    public static RecordingFilter byRange(String from, String to) {
        return of(null, null, null, null, null, null, null, from, to);
    }

    /** 强制指定状态（如 violations.csv 只导 VIOLATION_CONFIRMED），保留其余条件 */
    public RecordingFilter withStatuses(List<RecordingStatus> forced) {
        return new RecordingFilter(forced, keyword, assignedTo, priority, agentName, teamName,
                direction, from, toExclusive, unassignedOnly);
    }

    private static List<RecordingStatus> parseStatuses(String statuses) {
        if (statuses == null || statuses.isBlank()) {
            return null;
        }
        try {
            List<RecordingStatus> list = Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> RecordingStatus.valueOf(s.toUpperCase(Locale.ROOT)))
                    .toList();
            return list.isEmpty() ? null : list;
        } catch (IllegalArgumentException e) {
            throw new BizException("未知状态: " + statuses, HttpStatus.BAD_REQUEST);
        }
    }

    private static ReviewPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        try {
            return ReviewPriority.valueOf(priority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException("未知优先级: " + priority + "（仅支持 LOW / NORMAL / HIGH）", HttpStatus.BAD_REQUEST);
        }
    }

    private static String trimToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
