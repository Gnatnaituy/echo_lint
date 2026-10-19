package com.echolint.service;

import com.echolint.domain.CorpusLabel;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ViolationType;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.RecordingRepository;
import com.echolint.util.TimeRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台统计 + 报表统计（按状态/违规类型/坐席/团队/日维度）。
 *
 * <p>报表时间口径：优先 {@code callTime}，为空回退 {@code uploadTime}（见 RecordingRepository 聚合查询）。</p>
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 报表按日补零的最大跨度：超过后只返回有数据的日期，避免区间过大时点数爆炸 */
    private static final int MAX_FILLED_DAYS = 366;
    /** Top 坐席/团队条数 */
    private static final int TOP_N = 10;
    /** 未填写坐席/团队的展示名 */
    private static final String UNFILLED = "未填写";

    private final RecordingRepository recordingRepository;
    private final DictionaryWordRepository dictionaryWordRepository;
    private final CorpusEntryRepository corpusEntryRepository;

    public record DayCount(String date, long count) {
    }

    public record DictionaryStats(long total, long enabled, long minedPending) {
    }

    public record CorpusStats(long violation, long compliant) {
    }

    public record Overview(
            Map<String, Long> statusCounts,
            long totalUploads,
            long todayUploads,
            DictionaryStats dictionary,
            CorpusStats corpus,
            List<DayCount> last7Days) {
    }

    public record TypeCount(String type, String label, long count) {
    }

    public record NameCount(String name, long count) {
    }

    /**
     * 报表统计。
     *
     * @param total         录音总数
     * @param confirmed     人工确认违规数
     * @param falsePositive 人工判定误报数
     * @param byStatus      各状态数量（含全部状态键）
     * @param byViolationType 确认违规按类型分组（带中文标签）
     * @param topAgents     坐席 Top N（空坐席记为「未填写」）
     * @param topTeams      团队 Top N（空团队记为「未填写」）
     * @param byDay         按自然日统计（from/to 跨度不超过 366 天时补零）
     */
    public record Report(
            long total,
            long confirmed,
            long falsePositive,
            Map<String, Long> byStatus,
            List<TypeCount> byViolationType,
            List<NameCount> topAgents,
            List<NameCount> topTeams,
            List<DayCount> byDay) {
    }

    public Overview overview() {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (RecordingStatus s : RecordingStatus.values()) {
            statusCounts.put(s.name(), 0L);
        }
        for (Object[] row : recordingRepository.countGroupByStatus()) {
            statusCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        long totalUploads = recordingRepository.count();
        long todayUploads = recordingRepository.countUploadedSince(dayStart);

        List<DayCount> last7Days = new ArrayList<>();
        LocalDate since = today.minusDays(6);
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        for (Object[] row : recordingRepository.countByDaySince(since.atStartOfDay())) {
            byDay.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
        }
        for (int i = 0; i < 7; i++) {
            LocalDate d = since.plusDays(i);
            last7Days.add(new DayCount(d.toString(), byDay.getOrDefault(d, 0L)));
        }

        return new Overview(
                statusCounts,
                totalUploads,
                todayUploads,
                new DictionaryStats(
                        dictionaryWordRepository.count(),
                        dictionaryWordRepository.countByEnabledTrue(),
                        dictionaryWordRepository.countMinedPending()),
                new CorpusStats(
                        corpusEntryRepository.countByLabel(CorpusLabel.VIOLATION),
                        corpusEntryRepository.countByLabel(CorpusLabel.COMPLIANT)),
                last7Days);
    }

    /**
     * 报表统计（Batch 2a）。from/to 由调用方解析为左闭右开区间。
     */
    public Report report(TimeRanges.Range range) {
        LocalDateTime from = range.from();
        LocalDateTime to = range.toExclusive();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (RecordingStatus s : RecordingStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : recordingRepository.countByStatusBetween(from, to)) {
            long count = ((Number) row[1]).longValue();
            byStatus.put(String.valueOf(row[0]), count);
            total += count;
        }

        List<TypeCount> byViolationType = new ArrayList<>();
        for (Object[] row : recordingRepository.countConfirmedByViolationTypeBetween(from, to)) {
            // 已确认违规但类型缺失的历史数据按「其他」展示（ViolationType.fromCode(null) 会得到 NONE）
            String code = row[0] == null ? null : String.valueOf(row[0]);
            ViolationType type = code == null || code.isBlank()
                    ? ViolationType.OTHER
                    : ViolationType.fromCode(code);
            byViolationType.add(new TypeCount(type.name(), type.getLabel(),
                    ((Number) row[1]).longValue()));
        }
        byViolationType.sort(Comparator.comparingLong(TypeCount::count).reversed()
                .thenComparing(TypeCount::type));

        return new Report(
                total,
                byStatus.getOrDefault(RecordingStatus.VIOLATION_CONFIRMED.name(), 0L),
                byStatus.getOrDefault(RecordingStatus.FALSE_POSITIVE.name(), 0L),
                byStatus,
                byViolationType,
                topNames(recordingRepository.countByAgentBetween(from, to, PageRequest.of(0, TOP_N * 2))),
                topNames(recordingRepository.countByTeamBetween(from, to, PageRequest.of(0, TOP_N * 2))),
                dailyCounts(from, to));
    }

    /** 聚合结果为 (name, count)：空/空白名记为「未填写」，合并后按次数倒序取 Top N */
    private List<NameCount> topNames(List<Object[]> rows) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String name = row[0] == null ? "" : String.valueOf(row[0]).trim();
            merged.merge(name.isEmpty() ? UNFILLED : name, ((Number) row[1]).longValue(), Long::sum);
        }
        return merged.entrySet().stream()
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(NameCount::count).reversed()
                        .thenComparing(NameCount::name))
                .limit(TOP_N)
                .toList();
    }

    private List<DayCount> dailyCounts(LocalDateTime from, LocalDateTime to) {
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        for (Object[] row : recordingRepository.countByDayBetween(from, to)) {
            byDay.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).longValue());
        }
        List<DayCount> result = new ArrayList<>();
        LocalDate start = from.toLocalDate();
        LocalDate end = to.minusNanos(1).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 0 && days <= MAX_FILLED_DAYS) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                result.add(new DayCount(d.toString(), byDay.getOrDefault(d, 0L)));
            }
            return result;
        }
        // 区间过大（或未指定范围）时不补零，只返回有数据的日期
        byDay.forEach((date, count) -> result.add(new DayCount(date.toString(), count)));
        result.sort(Comparator.comparing(DayCount::date));
        return result;
    }
}
