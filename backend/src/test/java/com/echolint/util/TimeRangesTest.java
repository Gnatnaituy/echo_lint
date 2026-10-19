package com.echolint.util;

import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日期时间参数解析：ISO / "yyyy-MM-dd HH:mm:ss" / 纯日期，to 含当天。
 */
class TimeRangesTest {

    @Test
    void parsesIsoAndSpaceSeparatedDateTimes() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 20, 9, 30);
        assertEquals(expected, TimeRanges.parseDateTime("2026-10-20T09:30:00"));
        assertEquals(expected, TimeRanges.parseDateTime("2026-10-20 09:30:00"));
        assertEquals(expected, TimeRanges.parseDateTime(" 2026-10-20T09:30:00 "));
    }

    @Test
    void parsesDateOnlyAsStartOfDayAndBlankAsNull() {
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), TimeRanges.parseDateTime("2026-10-20"));
        assertNull(TimeRanges.parseDateTime(null));
        assertNull(TimeRanges.parseDateTime("   "));
    }

    @Test
    void invalidDateTimeIsRejectedAsBadRequest() {
        BizException e = assertThrows(BizException.class, () -> TimeRanges.parseDateTime("2026/10/20 09:30"));
        assertEquals(400, e.getStatus().value());
        assertTrue(e.getMessage().contains("时间格式不正确"));
    }

    @Test
    void rangeWithDateOnlyToIncludesTheWholeDay() {
        TimeRanges.Range range = TimeRanges.parseRange("2026-10-20", "2026-10-20");

        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), range.from());
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), range.toExclusive());
    }

    @Test
    void rangeWithDateTimeToIsInclusive() {
        TimeRanges.Range range = TimeRanges.parseRange(null, "2026-10-20T09:30:00");

        assertEquals(TimeRanges.MIN, range.from());
        LocalDateTime to = range.toExclusive();
        assertTrue(to.isAfter(LocalDateTime.of(2026, 10, 20, 9, 30, 0)));
        assertTrue(to.isBefore(LocalDateTime.of(2026, 10, 20, 9, 30, 1)));
    }

    @Test
    void rangeFallsBackToOpenBoundsWhenParamsMissing() {
        TimeRanges.Range range = TimeRanges.parseRange(null, null);

        assertEquals(TimeRanges.MIN, range.from());
        assertEquals(TimeRanges.MAX, range.toExclusive());
        assertFalse(range.isEmpty());
    }

    @Test
    void rangeRejectsInvalidBounds() {
        assertThrows(BizException.class, () -> TimeRanges.parseRange("not-a-date", null));
        assertThrows(BizException.class, () -> TimeRanges.parseRange(null, "2026-13-40"));
    }
}
