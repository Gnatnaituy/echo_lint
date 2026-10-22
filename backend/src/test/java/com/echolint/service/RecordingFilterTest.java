package com.echolint.service;

import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.dto.RecordingFilter;
import com.echolint.exception.BizException;
import com.echolint.util.TimeRanges;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 列表/导出过滤参数解析：状态、优先级、方向、时间范围与非法值。
 */
class RecordingFilterTest {

    @Test
    void parsesAllListFilters() {
        RecordingFilter filter = RecordingFilter.of("pending, NEEDS_REVIEW", " 退款 ", " Alice ",
                "high", " 张三 ", "客服一组", "inbound", "2026-10-01", "2026-10-20");

        assertEquals(List.of(RecordingStatus.PENDING, RecordingStatus.NEEDS_REVIEW), filter.statuses());
        assertEquals("退款", filter.keyword());
        assertEquals("Alice", filter.assignedTo());
        assertEquals(ReviewPriority.HIGH, filter.priority());
        assertEquals("张三", filter.agentName());
        assertEquals("客服一组", filter.teamName());
        assertEquals(CallDirection.INBOUND, filter.direction());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), filter.from());
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), filter.toExclusive());
    }

    @Test
    void blankFiltersStayNullAndDirectionUnknownIsKeptForNullFallback() {
        RecordingFilter empty = RecordingFilter.of(null, " ", null, "", null, null, "", null, null);
        assertNull(empty.statuses());
        assertNull(empty.keyword());
        assertNull(empty.assignedTo());
        assertNull(empty.priority());
        assertNull(empty.agentName());
        assertNull(empty.teamName());
        assertNull(empty.direction());
        assertEquals(TimeRanges.MIN, empty.from());
        assertEquals(TimeRanges.MAX, empty.toExclusive());

        assertEquals(CallDirection.UNKNOWN,
                RecordingFilter.of(null, null, null, null, null, null, "UNKNOWN", null, null).direction());
    }

    @Test
    void invalidStatusPriorityDirectionAndTimeAreRejected() {
        assertThrows(BizException.class,
                () -> RecordingFilter.of("NOT_A_STATUS", null, null, null, null, null, null, null, null));
        assertThrows(BizException.class,
                () -> RecordingFilter.of(null, null, null, "URGENT", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RecordingFilter.of(null, null, null, null, null, null, "SIDEWAYS", null, null));
        assertThrows(BizException.class,
                () -> RecordingFilter.of(null, null, null, null, null, null, null, "2026-99-99", null));
    }

    @Test
    void withStatusesKeepsOtherConditions() {
        RecordingFilter filter = RecordingFilter.byRange("2026-10-01", "2026-10-02")
                .withStatuses(List.of(RecordingStatus.VIOLATION_CONFIRMED));

        assertEquals(List.of(RecordingStatus.VIOLATION_CONFIRMED), filter.statuses());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), filter.from());
        assertEquals(LocalDateTime.of(2026, 10, 3, 0, 0), filter.toExclusive());
    }

    @Test
    void unassignedOnlyDefaultsOffAndIsKeptByWithStatuses() {
        assertFalse(RecordingFilter.of(null, null, null, null, null, null, null, null, null).unassignedOnly());

        RecordingFilter filter = RecordingFilter.of(null, null, "alice", null,
                null, null, null, null, null, true);

        assertTrue(filter.unassignedOnly());
        assertEquals("alice", filter.assignedTo());
        assertTrue(filter.withStatuses(List.of(RecordingStatus.NEEDS_REVIEW)).unassignedOnly());
    }
}
