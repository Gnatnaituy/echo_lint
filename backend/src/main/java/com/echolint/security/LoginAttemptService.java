package com.echolint.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 登录失败限速（审计 P2-7）：按 {@code username|ip} 统计 5 分钟滑动窗口内的失败次数， 达到 {@value #MAX_ATTEMPTS} 次后拒绝继续尝试（HTTP
 * 429 + {@code Retry-After}），成功登录清零。
 *
 * <p><b>单实例语义</b>：计数保存在进程内 {@link ConcurrentHashMap}，与当前内存会话/部署模型一致； 多实例部署需改为 Redis
 * 等共享存储。进程重启后计数清零（只会放宽，不会误封）。
 *
 * <p>IP 使用 {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}， <b>不直接信任</b> {@code
 * X-Forwarded-For}（可被客户端伪造，见审计报告对审计 IP 的说明）； 生产在 nginx 后置时由反代覆写 XFF、应用只看到回环/内网地址属于已知取舍，可在部署层用
 * 网关限速兜底。
 */
@Service
public class LoginAttemptService {

    /** 窗口内允许的最大失败次数 */
    static final int MAX_ATTEMPTS = 5;

    /** 失败计数滑动窗口 */
    static final Duration WINDOW = Duration.ofMinutes(5);

    /** 进程内最多跟踪的 key 数，防止大量随机用户名把 Map 撑大 */
    static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    /** 供单测注入可控时钟（窗口过期逻辑） */
    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /** 该 username+ip 是否已达到失败上限 */
    public boolean isBlocked(String username, String ip) {
        Deque<Instant> window = failures.get(key(username, ip));
        if (window == null) {
            return false;
        }
        synchronized (window) {
            prune(window);
            return window.size() >= MAX_ATTEMPTS;
        }
    }

    /** 距离可以再次尝试的剩余秒数（向上取整，至少 1s）；未封禁返回 0 */
    public long retryAfterSeconds(String username, String ip) {
        Deque<Instant> window = failures.get(key(username, ip));
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            prune(window);
            if (window.size() < MAX_ATTEMPTS) {
                return 0L;
            }
            Instant unlockAt = window.peekFirst().plus(WINDOW);
            long seconds = Duration.between(clock.instant(), unlockAt).getSeconds();
            return Math.max(1L, seconds);
        }
    }

    /** 记录一次失败（窗口外的旧记录先清理） */
    public void recordFailure(String username, String ip) {
        Deque<Instant> window =
                failures.computeIfAbsent(key(username, ip), k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window);
            window.addLast(clock.instant());
        }
        pruneTrackedKeysIfNeeded();
    }

    /** 登录成功后清零该 username+ip 的失败记录 */
    public void reset(String username, String ip) {
        failures.remove(key(username, ip));
    }

    private void prune(Deque<Instant> window) {
        Instant threshold = clock.instant().minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(threshold)) {
            window.pollFirst();
        }
    }

    /** Map 超过上限时清理已空/过期的 key（避免随机用户名导致内存增长） */
    private void pruneTrackedKeysIfNeeded() {
        if (failures.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        try {
            for (Map.Entry<String, Deque<Instant>> entry : failures.entrySet()) {
                Deque<Instant> window = entry.getValue();
                synchronized (window) {
                    prune(window);
                    if (window.isEmpty()) {
                        failures.remove(entry.getKey(), window);
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // 并发记录失败时跳过本轮清理，下次再试
        }
    }

    private static String key(String username, String ip) {
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String address = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        return user + "|" + address;
    }
}
