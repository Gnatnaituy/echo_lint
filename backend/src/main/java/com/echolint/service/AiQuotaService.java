package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI 配额与限流（Batch 3a），在每次真正发起模型调用前校验：
 *
 * <ul>
 *   <li><b>每日 token 配额</b>：{@code app.screen-models.daily-token-limit} （0=不限）。查 ai_invocations 当日已用
 *       token（输入+输出）合计，达到上限即拒绝；
 *   <li><b>每分钟请求数</b>：{@code app.screen-models.requests-per-minute} （0=不限）。进程内按供应商计数，按自然分钟滚动。
 * </ul>
 *
 * <p>超限抛 {@link BizException}（429），调用方不再发起请求；流水线捕获后降级人工复核。 计数在「检查时」递增：一旦某分钟超限，该分钟后续尝试都会失败，避免边界抖动放行。
 *
 * <p><b>并发语义（Batch 6 收尾）</b>：token 用量在调用结束后才写库，纯「先查后调」在并发下可能 让多个调用同时通过校验。这里增加进程内「预留-结算」：{@link
 * #checkBeforeCall} 原子预留一笔 保守估算 token（调用方必须在 finally 中 {@link Reservation#close()} 释放），校验条件为 「今日已用
 * + 在飞预留 &gt;= 上限」。预留在进程内，多实例部署时各实例独立预留，允许的超限量约为 「实例数 × 并发调用数 × 单次预估」，需在运维文档中知悉。
 */
@Slf4j
@Service
public class AiQuotaService {

    /** 单次调用的保守 token 预留估算（约 12k 字符分块 + few-shot/RAG 注入的上限量级） */
    static final long DEFAULT_RESERVATION_TOKENS = 4096;

    private final ScreenModelProperties properties;
    private final AiInvocationRepository invocationRepository;
    private final LongSupplier minuteSupplier;

    /** providerKey -> 当前分钟窗口计数（进程内，多实例部署时各自计数） */
    private final Map<String, MinuteWindow> windows = new ConcurrentHashMap<>();

    /** 进程内在飞调用预留的 token 合计（跨自然日自动归零） */
    private final AtomicLong reservedTokens = new AtomicLong();

    private volatile LocalDate reservedDate = LocalDate.now();

    @Autowired
    public AiQuotaService(
            ScreenModelProperties properties, AiInvocationRepository invocationRepository) {
        this(properties, invocationRepository, () -> System.currentTimeMillis() / 60_000L);
    }

    /** 测试用：注入可控的「当前分钟」 */
    AiQuotaService(
            ScreenModelProperties properties,
            AiInvocationRepository invocationRepository,
            LongSupplier minuteSupplier) {
        this.properties = properties;
        this.invocationRepository = invocationRepository;
        this.minuteSupplier = minuteSupplier;
    }

    /**
     * 调用前校验并原子预留一笔估算 token；超限抛 BizException（不发起模型调用）。
     *
     * <p>调用方必须在 finally 中关闭返回的 {@link Reservation}（释放预留），否则预留在进程内累积。
     */
    public Reservation checkBeforeCall(ScreenModelService.ActiveModel model) {
        Reservation reservation = reserveAgainstRemainingQuota();
        try {
            checkRequestsPerMinute(model);
            return reservation;
        } catch (RuntimeException e) {
            // 校验失败不会发起调用，立即释放预留，避免配额被永久占用
            reservation.close();
            throw e;
        }
    }

    /**
     * RAG embedding 调用前的每日 token 配额校验（embedding 没有 chat 模型句柄， 不参与「按供应商每分钟请求数」限流；用量仍写入
     * ai_invocations 参与每日合计）。 注：embedding 走批量调用、并发度低，这里不做在飞预留（保留原有的「已用 &gt;= 上限」语义）。
     */
    public void checkDailyTokensOnly() {
        long limit = properties.getDailyTokenLimit();
        if (limit <= 0) {
            return;
        }
        Long used = invocationRepository.sumTokensSince(LocalDate.now().atStartOfDay());
        long tokens = used == null ? 0L : used;
        if (tokens >= limit) {
            log.warn("AI 每日 token 配额已用尽：上限 {}，今日已用 {}", limit, tokens);
            throw new BizException(
                    "AI 每日 token 配额已用尽（上限 " + limit + "，今日已用 " + tokens + "），本次调用已跳过并降级人工复核",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /**
     * 按剩余配额原子预留一笔保守估算 token： 预留量 = min(4096, 剩余额度/2)，剩余额度极小时为 0（保持原有「已用 &gt;= 上限才拒绝」语义，
     * 不会因为预留把本来合法的调用挡掉）。校验条件为「今日已用 + 在飞预留 &gt;= 上限」。
     */
    private Reservation reserveAgainstRemainingQuota() {
        long limit = properties.getDailyTokenLimit();
        if (limit <= 0) {
            return new Reservation(0L);
        }
        rolloverReservationIfNewDay();
        Long used = invocationRepository.sumTokensSince(LocalDate.now().atStartOfDay());
        long tokens = used == null ? 0L : used;
        if (tokens >= limit) {
            log.warn("AI 每日 token 配额已用尽：上限 {}，今日已用 {}", limit, tokens);
            throw new BizException(
                    "AI 每日 token 配额已用尽（上限 " + limit + "，今日已用 " + tokens + "），本次调用已跳过并降级人工复核",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        long remaining = limit - tokens;
        long amount = Math.min(DEFAULT_RESERVATION_TOKENS, Math.max(0L, remaining / 2));
        long pending = reservedTokens.addAndGet(amount);
        if (tokens + pending >= limit) {
            reservedTokens.addAndGet(-amount);
            log.warn("AI 每日 token 配额已用尽（含在飞预留）：上限 {}，今日已用 {}，在飞预留 {}", limit, tokens, pending);
            throw new BizException(
                    "AI 每日 token 配额已用尽（上限 "
                            + limit
                            + "，今日已用 "
                            + tokens
                            + "，在飞预留 "
                            + pending
                            + "），本次调用已跳过并降级人工复核",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        return new Reservation(amount);
    }

    private void rolloverReservationIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(reservedDate)) {
            reservedDate = today;
            reservedTokens.set(0L);
        }
    }

    /** 「预留-结算」中的预留句柄：调用结束（成功/失败）后 close 释放，close 幂等。 */
    public final class Reservation implements AutoCloseable {

        private final long amount;
        private boolean released;

        Reservation(long amount) {
            this.amount = amount;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            reservedTokens.addAndGet(-amount);
        }
    }

    private void checkRequestsPerMinute(ScreenModelService.ActiveModel model) {
        int limit = properties.getRequestsPerMinute();
        if (limit <= 0) {
            return;
        }
        String providerKey =
                model == null || model.providerId() == null ? "unknown" : model.providerId();
        long minute = minuteSupplier.getAsLong();
        MinuteWindow window = windows.computeIfAbsent(providerKey, key -> new MinuteWindow());
        int count;
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.count = 0;
            }
            window.count++;
            count = window.count;
        }
        if (count > limit) {
            log.warn("AI 调用频率超限：供应商 {} 第 {} 次/分钟（上限 {}）", providerKey, count, limit);
            throw new BizException(
                    "AI 调用频率超限（供应商 " + providerKey + "，每分钟最多 " + limit + " 次），本次调用已跳过并降级人工复核",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private static final class MinuteWindow {
        long minute = Long.MIN_VALUE;
        int count;
    }
}
