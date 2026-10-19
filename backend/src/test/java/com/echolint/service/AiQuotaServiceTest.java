package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 配额与限流（Batch 3a）：每日 token 配额（DB 累计）、每供应商每分钟请求数（进程内），
 * 0 = 不限；超限抛 429 且不发起调用。
 */
@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock
    private AiInvocationRepository repository;

    private ScreenModelProperties properties;
    private final AtomicLong minute = new AtomicLong(1_000L);

    @BeforeEach
    void setUp() {
        properties = new ScreenModelProperties();
    }

    private AiQuotaService service() {
        return new AiQuotaService(properties, repository, minute::get);
    }

    private ScreenModelService.ActiveModel model(String providerId) {
        return new ScreenModelService.ActiveModel(
                providerId, providerId, "https://api.example.com", "model-a", "/v1/chat/completions",
                true, true, null, Map.of(), 30, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void dailyTokenLimitBlocksWhenExhausted() {
        properties.setDailyTokenLimit(1000);
        when(repository.sumTokensSince(any())).thenReturn(999L);
        AiQuotaService service = service();

        assertDoesNotThrow(() -> service.checkBeforeCall(model("openai")));

        when(repository.sumTokensSince(any())).thenReturn(1000L);
        BizException error = assertThrows(BizException.class,
                () -> service.checkBeforeCall(model("openai")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertTrue(error.getMessage().contains("token 配额"), error.getMessage());
    }

    @Test
    void dailyTokenLimitNullSumIsTreatedAsZero() {
        properties.setDailyTokenLimit(100);
        when(repository.sumTokensSince(any())).thenReturn(null);

        assertDoesNotThrow(() -> service().checkBeforeCall(model("openai")));
    }

    @Test
    void zeroDailyLimitNeverQueriesDatabase() {
        properties.setDailyTokenLimit(0);

        service().checkBeforeCall(model("openai"));

        verify(repository, never()).sumTokensSince(any());
    }

    @Test
    void perMinuteLimitBlocksThirdCallWithinSameMinute() {
        properties.setRequestsPerMinute(2);
        AiQuotaService service = service();
        ScreenModelService.ActiveModel model = model("openai");

        service.checkBeforeCall(model);
        service.checkBeforeCall(model);
        BizException error = assertThrows(BizException.class, () -> service.checkBeforeCall(model));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertTrue(error.getMessage().contains("频率超限"), error.getMessage());
        verify(repository, never()).sumTokensSince(any());
    }

    @Test
    void perMinuteCounterRollsOverWithNewMinute() {
        properties.setRequestsPerMinute(1);
        AiQuotaService service = service();
        ScreenModelService.ActiveModel model = model("openai");

        service.checkBeforeCall(model);
        assertThrows(BizException.class, () -> service.checkBeforeCall(model));

        minute.incrementAndGet();
        assertDoesNotThrow(() -> service.checkBeforeCall(model), "跨分钟后计数应重置");
    }

    @Test
    void perMinuteCounterIsIsolatedPerProvider() {
        properties.setRequestsPerMinute(1);
        AiQuotaService service = service();

        service.checkBeforeCall(model("openai"));
        assertDoesNotThrow(() -> service.checkBeforeCall(model("deepseek")));
    }

    @Test
    void zeroPerMinuteLimitIsUnlimited() {
        properties.setRequestsPerMinute(0);
        AiQuotaService service = service();

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.checkBeforeCall(model("openai")));
        }
    }

    @Test
    void bothLimitsCanBeEnforcedTogether() {
        properties.setDailyTokenLimit(10);
        properties.setRequestsPerMinute(1);
        when(repository.sumTokensSince(any())).thenReturn(0L);
        AiQuotaService service = service();
        ScreenModelService.ActiveModel model = model("openai");

        service.checkBeforeCall(model);
        assertThrows(BizException.class, () -> service.checkBeforeCall(model));

        minute.incrementAndGet();
        when(repository.sumTokensSince(any())).thenReturn(10L);
        BizException error = assertThrows(BizException.class, () -> service.checkBeforeCall(model));
        assertTrue(error.getMessage().contains("token 配额"));
    }
}
