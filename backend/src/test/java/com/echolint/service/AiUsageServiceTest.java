package com.echolint.service;

import com.echolint.repository.AiInvocationRepository;
import com.echolint.util.TimeRanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 成本统计（Batch 3a）：汇总 + byDay/byProvider/byModel；空数据返回零值与空列表。
 */
@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock
    private AiInvocationRepository repository;

    private AiUsageService service;
    private final TimeRanges.Range range = new TimeRanges.Range(
            LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));

    @BeforeEach
    void setUp() {
        service = new AiUsageService(repository);
    }

    @Test
    void emptyRangeReturnsZeroesAndEmptyLists() {
        when(repository.countSuccessfulBetween(any(), any())).thenReturn(0L);
        when(repository.sumInputTokensBetween(any(), any())).thenReturn(0L);
        when(repository.sumOutputTokensBetween(any(), any())).thenReturn(0L);
        when(repository.sumCostMicrosBetween(any(), any())).thenReturn(0L);
        when(repository.aggregateByDayBetween(any(), any())).thenReturn(List.of());
        when(repository.aggregateByProviderBetween(any(), any())).thenReturn(List.of());
        when(repository.aggregateByModelBetween(any(), any())).thenReturn(List.of());

        AiUsageService.Report report = service.report(range);

        assertEquals(0, report.requests());
        assertEquals(0, report.inputTokens());
        assertEquals(0, report.outputTokens());
        assertEquals(0, report.costUsd().compareTo(BigDecimal.ZERO));
        assertTrue(report.byDay().isEmpty());
        assertTrue(report.byProvider().isEmpty());
        assertTrue(report.byModel().isEmpty());
        verify(repository).countSuccessfulBetween(range.from(), range.toExclusive());
    }

    @Test
    void aggregatesTotalsAndBreakdowns() {
        when(repository.countSuccessfulBetween(any(), any())).thenReturn(3L);
        when(repository.sumInputTokensBetween(any(), any())).thenReturn(1500L);
        when(repository.sumOutputTokensBetween(any(), any())).thenReturn(600L);
        when(repository.sumCostMicrosBetween(any(), any())).thenReturn(2_250L);
        when(repository.aggregateByDayBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{Date.valueOf("2026-10-20"), 2L, 1000L, 400L, 1500L},
                new Object[]{Date.valueOf("2026-10-21"), 1L, 500L, 200L, 750L}));
        when(repository.aggregateByProviderBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"deepseek", 2L, 1200L, 500L, 2000L},
                new Object[]{null, 1L, 300L, 100L, 250L}));
        when(repository.aggregateByModelBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"deepseek-flash", 3L, 1500L, 600L, 2250L}));

        AiUsageService.Report report = service.report(range);

        assertEquals(3, report.requests());
        assertEquals(1500, report.inputTokens());
        assertEquals(600, report.outputTokens());
        assertEquals(new BigDecimal("0.002250"), report.costUsd());

        assertEquals(2, report.byDay().size());
        assertEquals("2026-10-20", report.byDay().get(0).date());
        assertEquals(2, report.byDay().get(0).requests());
        assertEquals(1000, report.byDay().get(0).inputTokens());
        assertEquals(400, report.byDay().get(0).outputTokens());
        assertEquals(new BigDecimal("0.001500"), report.byDay().get(0).costUsd());

        assertEquals(2, report.byProvider().size());
        assertEquals("deepseek", report.byProvider().get(0).provider());
        assertEquals(2, report.byProvider().get(0).requests());
        assertEquals("unknown", report.byProvider().get(1).provider(), "缺失供应商归入 unknown");

        assertEquals(1, report.byModel().size());
        assertEquals("deepseek-flash", report.byModel().get(0).model());
        assertEquals(new BigDecimal("0.002250"), report.byModel().get(0).costUsd());
    }

    @Test
    void nullSumsAreTreatedAsZero() {
        when(repository.countSuccessfulBetween(any(), any())).thenReturn(0L);
        when(repository.sumInputTokensBetween(any(), any())).thenReturn(null);
        when(repository.sumOutputTokensBetween(any(), any())).thenReturn(null);
        when(repository.sumCostMicrosBetween(any(), any())).thenReturn(null);
        when(repository.aggregateByDayBetween(any(), any())).thenReturn(List.of());
        when(repository.aggregateByProviderBetween(any(), any())).thenReturn(List.of());
        when(repository.aggregateByModelBetween(any(), any())).thenReturn(List.of());

        AiUsageService.Report report = service.report(range);

        assertEquals(0, report.inputTokens());
        assertEquals(0, report.outputTokens());
        assertEquals(0, report.costUsd().compareTo(BigDecimal.ZERO));
    }
}
