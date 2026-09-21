package com.echolint.service;

import com.echolint.domain.CorpusLabel;
import com.echolint.domain.RecordingStatus;
import com.echolint.repository.CorpusEntryRepository;
import com.echolint.repository.DictionaryWordRepository;
import com.echolint.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台统计
 */
@Service
@RequiredArgsConstructor
public class StatsService {

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
}