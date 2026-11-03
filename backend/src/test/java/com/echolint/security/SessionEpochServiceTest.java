package com.echolint.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 会话纪元服务：缺省 0、bump 自增、按用户隔离、用户名归一化、并发自增原子性。 */
class SessionEpochServiceTest {

    private final SessionEpochService service = new SessionEpochService();

    @Test
    void unknownUserStartsAtZero() {
        assertEquals(0L, service.current("alice"));
    }

    @Test
    void bumpIncrementsAndReturnsNewEpoch() {
        assertEquals(1L, service.bump("alice"));
        assertEquals(1L, service.current("alice"));
        assertEquals(2L, service.bump("alice"));
        assertEquals(2L, service.current("alice"));
    }

    @Test
    void epochsAreIsolatedPerUser() {
        service.bump("alice");
        service.bump("alice");

        assertEquals(2L, service.current("alice"));
        assertEquals(0L, service.current("bob"));

        assertEquals(1L, service.bump("bob"));
        assertEquals(2L, service.current("alice"), "bump 其它用户不能影响 alice 的纪元");
    }

    @Test
    void usernameIsNormalizedForLookup() {
        service.bump("  Alice ");

        assertEquals(1L, service.current("alice"));
        assertEquals(1L, service.current("ALICE"));
        assertEquals(2L, service.bump("alice"));
    }

    @Test
    void nullUsernameIsTreatedAsEmptyKeyWithoutThrowing() {
        assertEquals(0L, service.current(null));
        assertEquals(1L, service.bump(null));
        assertEquals(1L, service.current(""));
    }

    @Test
    void concurrentBumpsAreAtomic() throws Exception {
        int threads = 8;
        int perThread = 50;
        int expected = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(
                        () -> {
                            start.await();
                            for (int j = 0; j < perThread; j++) {
                                service.bump("alice");
                            }
                            return null;
                        });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发 bump 应在 10s 内完成");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(expected, service.current("alice"), "并发 bump 不得丢失任何一次自增");
    }
}
