package com.echolint.util;

import com.echolint.exception.BizException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 日期时间参数解析与范围换算。
 *
 * <p>对外（查询参数/上传参数）接受两种写法：</p>
 * <ul>
 *   <li>纯日期 {@code yyyy-MM-dd}；</li>
 *   <li>日期时间 {@code yyyy-MM-dd'T'HH:mm[:ss]}（ISO-8601）或 {@code yyyy-MM-dd HH:mm[:ss]}。</li>
 * </ul>
 *
 * <p>范围语义：{@code from} 含起点，{@code to} 含当天/当刻（内部转换为左闭右开的 {@code toExclusive}）。</p>
 */
public final class TimeRanges {

    /** 未指定 from 时使用的下界（覆盖历史数据，避免 SQL 里出现 null 参数分支） */
    public static final LocalDateTime MIN = LocalDateTime.of(1970, 1, 1, 0, 0);

    /** 未指定 to 时使用的上界 */
    public static final LocalDateTime MAX = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    private static final String FORMAT_HINT = "（支持 yyyy-MM-dd 或 ISO 日期时间，如 2026-10-20T09:30:00）";

    private TimeRanges() {
    }

    /** 左闭右开的时间范围 */
    public record Range(LocalDateTime from, LocalDateTime toExclusive) {
        public boolean isEmpty() {
            return from != null && toExclusive != null && !from.isBefore(toExclusive);
        }
    }

    /**
     * 解析一个日期时间参数（上传的 callTime）。空值返回 null；非法值 400。
     */
    public static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式不正确: " + value + FORMAT_HINT, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 解析查询范围。from/to 均可空：空值分别退化为 {@link #MIN}/{@link #MAX}。
     * to 为纯日期时包含当天（右界取次日零点）。
     */
    public static Range parseRange(String from, String to) {
        LocalDateTime fromValue = from == null || from.isBlank() ? MIN : parseDateTime(from);
        LocalDateTime toValue;
        if (to == null || to.isBlank()) {
            toValue = MAX;
        } else {
            String value = to.trim();
            LocalDate dateOnly = tryDate(value);
            toValue = dateOnly != null
                    ? dateOnly.plusDays(1).atStartOfDay()
                    // 日期时间作为「含当刻」处理：右开区间加 1 纳秒，等价于 <=
                    : parseDateTime(value).plusNanos(1);
        }
        return new Range(fromValue, toValue);
    }

    private static LocalDate tryDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
