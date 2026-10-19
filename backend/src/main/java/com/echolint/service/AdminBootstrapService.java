package com.echolint.service;

import com.echolint.config.AppProperties;
import com.echolint.domain.UserRole;
import com.echolint.entity.AppUser;
import com.echolint.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 首次启动引导：用户表为空时创建一个管理员。
 *
 * <p>账号来自 {@code APP_ADMIN_USERNAME}（默认 admin）/ {@code APP_ADMIN_PASSWORD}（默认 admin12345），
 * 密码以 BCrypt 落库，并在启动日志 WARN 提示修改默认密码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapService implements ApplicationRunner {

    static final String DEFAULT_USERNAME = "admin";
    static final String DEFAULT_PASSWORD = "admin12345";
    static final String DEFAULT_DISPLAY_NAME = "管理员";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        String username = normalize(appProperties.getAdmin().getUsername());
        if (!StringUtils.hasText(username)) {
            username = DEFAULT_USERNAME;
        }
        String rawPassword = appProperties.getAdmin().getPassword();
        if (!StringUtils.hasText(rawPassword)) {
            rawPassword = DEFAULT_PASSWORD;
        }
        if (rawPassword.length() < 8) {
            log.warn("APP_ADMIN_PASSWORD 长度不足 8 位，不符合密码策略，请登录后立即修改");
        }

        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setDisplayName(DEFAULT_DISPLAY_NAME);
        admin.setRole(UserRole.ADMIN);
        admin.setEnabled(true);
        userRepository.save(admin);

        log.warn("用户表为空，已创建初始管理员账号「{}」（密码来自 APP_ADMIN_PASSWORD，默认 admin12345）。"
                + "请立即登录并修改默认密码！", username);
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
