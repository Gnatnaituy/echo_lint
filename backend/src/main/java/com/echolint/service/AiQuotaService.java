package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AI 配额与限流（Batch 3a），在每次真正发起模型调用前校验：
 *
 * <ul>
 *   <li><b>每日 token 配额</b>：{@code app.screen-models.daily-token-limit}
 *       （0=不限）。查 ai_invocations 当日已用 token（输入+输出）合计，达到上限即拒绝；</li>
 *   <li><b>每分钟请求数</b>：{@code app.screen-models.requests-per-minute}
 *       （0=不限）。进程内按供应商计数，按自然分钟滚动。</li>
 * </ul>
 *
 * <p>超限抛 {@link BizException}（429），调用方不再发起请求；流水线捕获后降级人工复核。
 * 计数在「检查时」递增：一旦某分钟超限，该分钟后续尝试都会失败，避免边界抖动放行。</p>
 */
@Slf4j
@Service
public class AiQuotaService {

    private final ScreenModelProperties properties;
    private final AiInvocationRepository invocationRepository;
    private final LongSupplier minuteSupplier;

    /** providerKey -> 当前分钟窗口计数（进程内，多实例部署时各自计数） */
    private final Map<String, MinuteWindow> windows = new ConcurrentHashMap<>();

    @Autowired
    public AiQuotaService(ScreenModelProperties properties, AiInvocationRepository invocationRepository) {
        this(properties, invocationRepository, () -> System.currentTimeMillis() / 60_000L);
    }

    /** 测试用：注入可控的「当前分钟」 */
    AiQuotaService(ScreenModelProperties properties, AiInvocationRepository invocationRepository,
                   LongSupplier minuteSupplier) {
        this.properties = properties;
        this.invocationRepository = invocationRepository;
        this.minuteSupplier = minuteSupplier;
    }

    /** 调用前校验，超限抛 BizException（不发起模型调用） */
    public void checkBeforeCall(ScreenModelService.ActiveModel model) {
        checkDailyTokens();
        checkRequestsPerMinute(model);
    }

    /**
     * RAG embedding 调用前的每日 token 配额校验（embedding 没有 chat 模型句柄，
     * 不参与「按供应商每分钟请求数」限流；用量仍写入 ai_invocations 参与每日合计）。
     */
    public void checkDailyTokensOnly() {
        checkDailyTokens();
    }

    private void checkDailyTokens() {
        long limit = properties.getDailyTokenLimit();
        if (limit <= 0) {
            return;
        }
        Long used = invocationRepository.sumTokensSince(LocalDate.now().atStartOfDay());
        long tokens = used == null ? 0L : used;
        if (tokens >= limit) {
            log.warn("AI 每日 token 配额已用尽：上限 {}，今日已用 {}", limit, tokens);
            throw new BizException("AI 每日 token 配额已用尽（上限 " + limit + "，今日已用 " + tokens
                    + "），本次调用已跳过并降级人工复核", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private void checkRequestsPerMinute(ScreenModelService.ActiveModel model) {
        int limit = properties.getRequestsPerMinute();
        if (limit <= 0) {
            return;
        }
        String providerKey = model == null || model.providerId() == null ? "unknown" : model.providerId();
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
            throw new BizException("AI 调用频率超限（供应商 " + providerKey + "，每分钟最多 " + limit
                    + " 次），本次调用已跳过并降级人工复核", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private static final class MinuteWindow {
        long minute = Long.MIN_VALUE;
        int count;
    }
}
