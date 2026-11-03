package com.echolint.security;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 会话纪元（Session Epoch）：账号状态变更后让旧会话立即失效。
 *
 * <p>背景：会话里保存的是登录时刻的 {@link AppUserDetails} 快照（角色、启用状态）， 之后管理员「禁用账号 / 降级角色 /
 * 重置密码」或用户本人「修改密码」都不会自动让已登录会话失效， 旧会话在过期前仍可访问。本类为每个用户名维护一个单调递增的纪元号：
 *
 * <ul>
 *   <li>登录成功：把当时的纪元号写入 session（{@code AuthService#login}）；
 *   <li>账号状态变化：{@link #bump(String)} 让纪元 +1；
 *   <li>每个请求：{@link UserSessionGuardFilter} 比对 session 里的纪元与当前值，不一致即 401 并销毁会话。
 * </ul>
 *
 * <p><b>单实例语义</b>：纪元保存在进程内 {@link ConcurrentHashMap}，与当前会话存储（Tomcat 内存会话） 的生命周期一致；多实例部署时需要改为 Redis
 * / 数据库等共享存储，否则 A 实例 bump 不影响 B 实例上的旧会话。 进程重启后 Map 清空（回到 0），而内存会话同样全部丢失，用户本来就需要重新登录，不会造成误放行。
 */
@Service
public class SessionEpochService {

    /** session 中保存纪元号的属性名（供登录写入与过滤器读取） */
    public static final String SESSION_ATTRIBUTE = "echolint.session.epoch";

    /** 用户名 → 当前纪元；缺省（从未 bump 过）为 0 */
    private final ConcurrentHashMap<String, Long> epochs = new ConcurrentHashMap<>();

    /** 当前纪元号；未知用户返回 0（与登录时写入的初始值一致）。 */
    public long current(String username) {
        Long epoch = epochs.get(normalize(username));
        return epoch == null ? 0L : epoch;
    }

    /** 纪元 +1 并返回新值（原子操作）。账号状态变更后调用，使该用户所有旧会话在下一次请求时失效。 */
    public long bump(String username) {
        return epochs.merge(normalize(username), 1L, Long::sum);
    }

    /** 与 {@code AppUser.username} 一致：去空白 + 小写，避免大小写造成纪元键不一致 */
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
