package com.echolint.service;

import com.echolint.repository.AiInvocationRepository;
import com.echolint.util.TimeRanges;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 成本统计（Batch 3a）：按时间范围汇总 tokens / 成本，并给出 byDay / byProvider / byModel 明细。
 *
 * <p>只统计成功调用（失败调用没有 usage，也不产生费用）。成本为调用时快照（costMicros），
 * 因此历史账目不会因为供应商改价而变化。</p>
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final String UNKNOWN = "unknown";
    private static final int COST_SCALE = 6;

    private final AiInvocationRepository repository;

    /** 时间范围明细（from 含、to 为右开区间，由 TimeRanges 统一解析） */
    public Report report(TimeRanges.Range range) {
        LocalDateTime from = range.from();
        LocalDateTime to = range.toExclusive();

        long requests = repository.countSuccessfulBetween(from, to);
        long inputTokens = nz(repository.sumInputTokensBetween(from, to));
        long outputTokens = nz(repository.sumOutputTokensBetween(from, to));
        long costMicros = nz(repository.sumCostMicrosBetween(from, to));

        return new Report(requests, inputTokens, outputTokens, usd(costMicros),
                byDay(from, to), byProvider(from, to), byModel(from, to));
    }

    private List<DayUsage> byDay(LocalDateTime from, LocalDateTime to) {
        List<DayUsage> result = new ArrayList<>();
        for (Object[] row : repository.aggregateByDayBetween(from, to)) {
            result.add(new DayUsage(
                    dateOf(row[0]),
                    number(row[1]),
                    number(row[2]),
                    number(row[3]),
                    usd(number(row[4]))));
        }
        return result;
    }

    private List<ProviderUsage> byProvider(LocalDateTime from, LocalDateTime to) {
        List<ProviderUsage> result = new ArrayList<>();
        for (Object[] row : repository.aggregateByProviderBetween(from, to)) {
            result.add(new ProviderUsage(
                    unknownIfBlank(row[0]),
                    number(row[1]),
                    number(row[2]),
                    number(row[3]),
                    usd(number(row[4]))));
        }
        return result;
    }

    private List<ModelUsage> byModel(LocalDateTime from, LocalDateTime to) {
        List<ModelUsage> result = new ArrayList<>();
        for (Object[] row : repository.aggregateByModelBetween(from, to)) {
            result.add(new ModelUsage(
                    unknownIfBlank(row[0]),
                    number(row[1]),
                    number(row[2]),
                    number(row[3]),
                    usd(number(row[4]))));
        }
        return result;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 微美元 → USD（保留 6 位小数，前端按数字展示） */
    static BigDecimal usd(long costMicros) {
        return BigDecimal.valueOf(costMicros, COST_SCALE);
    }

    private static String dateOf(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String unknownIfBlank(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? UNKNOWN : text;
    }

    // ------------------------------------------------------------------ DTO

    public record DayUsage(
            String date,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {
    }

    public record ProviderUsage(
            String provider,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {
    }

    public record ModelUsage(
            String model,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {
    }

    /**
     * 与前端契约一致的扁平响应：汇总在顶层，明细在 byDay / byProvider / byModel。
     */
    public record Report(
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            List<DayUsage> byDay,
            List<ProviderUsage> byProvider,
            List<ModelUsage> byModel) {
    }
}
