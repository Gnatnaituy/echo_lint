package com.echolint.service;

import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.util.TimeRanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 报表统计：状态/违规类型/坐席团队 Top/按日聚合，空坐席记「未填写」，日期补零。
 */
@ExtendWith(MockitoExtension.class)
class StatsReportServiceTest {

    @Mock
    private RecordingRepository recordingRepository;
    @Mock
    private DictionaryWordRepository dictionaryWordRepository;
    @Mock
    private CorpusEntryRepository corpusEntryRepository;

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(recordingRepository, dictionaryWordRepository, corpusEntryRepository);
    }

    @Test
    void reportAggregatesStatusTypesAgentsTeamsAndFillsMissingDays() {
        when(recordingRepository.countByStatusBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"COMPLIANT", 5L},
                new Object[]{"VIOLATION_CONFIRMED", 8L},
                new Object[]{"FALSE_POSITIVE", 3L}));
        when(recordingRepository.countConfirmedByViolationTypeBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"THREAT", 2L},
                new Object[]{"INSULT", 5L},
                new Object[]{null, 1L}));
        when(recordingRepository.countByAgentBetween(any(), any(), any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{null, 4L},
                new Object[]{"  ", 2L},
                new Object[]{"张三", 5L}));
        when(recordingRepository.countByTeamBetween(any(), any(), any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{"A组", 3L}));
        when(recordingRepository.countByDayBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{java.sql.Date.valueOf("2026-10-19"), 2L}));

        StatsService.Report report = service.report(TimeRanges.parseRange("2026-10-19", "2026-10-20"));

        assertEquals(16, report.total());
        assertEquals(8, report.confirmed());
        assertEquals(3, report.falsePositive());
        assertEquals(9, report.byStatus().size(), "byStatus 应含全部状态键");
        assertEquals(0L, report.byStatus().get("PENDING"));
        assertEquals(5L, report.byStatus().get("COMPLIANT"));
        assertEquals(16L, report.byStatus().values().stream().mapToLong(Long::longValue).sum());

        assertEquals(List.of(
                new StatsService.TypeCount("INSULT", "辱骂", 5),
                new StatsService.TypeCount("THREAT", "威胁", 2),
                new StatsService.TypeCount("OTHER", "其他", 1)),
                report.byViolationType());

        assertEquals(List.of(
                new StatsService.NameCount("未填写", 6),
                new StatsService.NameCount("张三", 5)),
                report.topAgents());
        assertEquals(List.of(new StatsService.NameCount("A组", 3)), report.topTeams());

        assertEquals(List.of(
                new StatsService.DayCount("2026-10-19", 2),
                new StatsService.DayCount("2026-10-20", 0)),
                report.byDay(), "缺失日期应补零");
    }

    @Test
    void largeRangeReturnsOnlyDaysWithData() {
        when(recordingRepository.countByDayBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{java.sql.Date.valueOf("2026-10-19"), 2L}));

        StatsService.Report report = service.report(
                TimeRanges.parseRange("2020-01-01", "2026-12-31"));

        assertEquals(List.of(new StatsService.DayCount("2026-10-19", 2)), report.byDay());
    }

    @Test
    void topNamesMergedAndLimitedToTen() {
        List<Object[]> agents = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            agents.add(new Object[]{"坐席" + i, (long) (100 - i)});
        }
        when(recordingRepository.countByAgentBetween(any(), any(), any(Pageable.class))).thenReturn(agents);
        when(recordingRepository.countByTeamBetween(any(), any(), any(Pageable.class))).thenReturn(List.of());

        StatsService.Report report = service.report(TimeRanges.parseRange(null, null));

        assertEquals(10, report.topAgents().size());
        assertEquals("坐席0", report.topAgents().get(0).name());
        assertEquals(100, report.topAgents().get(0).count());
        assertTrue(report.topTeams().isEmpty());
    }

    @Test
    void emptyDataYieldsZeroedReport() {
        StatsService.Report report = service.report(
                TimeRanges.parseRange("2026-10-20", "2026-10-20"));

        assertEquals(0, report.total());
        assertEquals(0, report.confirmed());
        assertEquals(0, report.falsePositive());
        assertEquals(9, report.byStatus().size());
        assertTrue(report.byViolationType().isEmpty());
        assertTrue(report.topAgents().isEmpty());
        assertEquals(List.of(new StatsService.DayCount("2026-10-20", 0)), report.byDay());
    }

    @Test
    void reportQueriesUseParsedRangeBounds() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 3, 0, 0);

        service.report(TimeRanges.parseRange("2026-10-01", "2026-10-02"));

        verify(recordingRepository).countByStatusBetween(from, to);
        verify(recordingRepository).countByDayBetween(from, to);
    }
}
