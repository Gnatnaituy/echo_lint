package com.echolint.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 登录失败限速（P2-7）：5 次/5 分钟窗口封禁、Retry-After 计算、窗口滚动、按 key 隔离、成功清零。 */
class LoginAttemptServiceTest {

    /** 可手动推进的时钟，避免测试等真实 5 分钟 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service = new LoginAttemptService(clock);

    private void failTimes(String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username, ip);
        }
    }

    @Test
    void notBlockedBelowLimitThenBlockedAtFifthFailure() {
        failTimes("alice", "1.1.1.1", 4);
        assertFalse(service.isBlocked("alice", "1.1.1.1"), "4 次失败不应封禁");
        assertEquals(0L, service.retryAfterSeconds("alice", "1.1.1.1"));

        service.recordFailure("alice", "1.1.1.1");

        assertTrue(service.isBlocked("alice", "1.1.1.1"), "第 5 次失败应封禁");
        assertEquals(
                LoginAttemptService.WINDOW.getSeconds(),
                service.retryAfterSeconds("alice", "1.1.1.1"),
                "首次失败后满 5 分钟才解锁");
    }

    @Test
    void retryAfterCountsDownAndWindowSlides() {
        failTimes("alice", "1.1.1.1", 5);

        clock.advance(Duration.ofMinutes(2));
        assertEquals(180L, service.retryAfterSeconds("alice", "1.1.1.1"));

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        assertFalse(service.isBlocked("alice", "1.1.1.1"), "5 分钟窗口滑过后自动解封");
        assertEquals(0L, service.retryAfterSeconds("alice", "1.1.1.1"));
    }

    @Test
    void successfulLoginResetsFailures() {
        failTimes("alice", "1.1.1.1", 4);

        service.reset("alice", "1.1.1.1");

        assertFalse(service.isBlocked("alice", "1.1.1.1"));
        failTimes("alice", "1.1.1.1", 4);
        assertFalse(service.isBlocked("alice", "1.1.1.1"), "reset 之后重新累计，不应继承旧计数");
    }

    @Test
    void keysAreIsolatedByUsernameAndIp() {
        failTimes("alice", "1.1.1.1", 5);

        assertTrue(service.isBlocked("alice", "1.1.1.1"));
        assertFalse(service.isBlocked("bob", "1.1.1.1"), "同 IP 的其它账号不受影响");
        assertFalse(service.isBlocked("alice", "2.2.2.2"), "同账号换 IP 不受影响");
    }

    @Test
    void usernameAndIpAreNormalizedAndNullSafe() {
        failTimes("  Alice ", " 10.0.0.1 ", 5);

        assertTrue(service.isBlocked("alice", "10.0.0.1"));
        assertTrue(service.isBlocked("ALICE", "10.0.0.1"));
        assertFalse(service.isBlocked(null, null), "null 参数不应抛异常");
        service.recordFailure(null, null);
        assertFalse(service.isBlocked(null, null));
    }
}
