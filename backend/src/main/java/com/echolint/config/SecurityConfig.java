package com.echolint.config;

import com.echolint.security.ApiKeyAuthFilter;
import com.echolint.security.AppUserDetailsService;
import com.echolint.security.JsonAccessDeniedHandler;
import com.echolint.security.JsonAuthenticationEntryPoint;
import com.echolint.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 会话认证 + URL 级权限矩阵。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>登录/登出由 {@code AuthController} 自行实现（JSON 接口，无重定向），
 *       登录成功后手动 changeSessionId 并把 SecurityContext 写入会话；</li>
 *   <li>JSESSIONID 为 HttpOnly + SameSite=Lax（见 application.yml），前端不存 token；</li>
 *   <li>CSRF 关闭、formLogin/httpBasic 关闭；401/403 统一返回 {code,message,timestamp} JSON；</li>
 *   <li>前端只负责按角色隐藏入口，权限必须由这里的 URL 规则独立校验。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   JsonAuthenticationEntryPoint authenticationEntryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler,
                                                   ApiKeyService apiKeyService,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 登出由 AuthController 手动处理（审计 + invalidate），不启用默认 /logout 过滤器
                .logout(AbstractHttpConfigurer::disable)
                // 开放 API 鉴权：/api/open/** 在下方 permitAll，由该过滤器校验 X-API-Key
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 容器内部 ERROR 转发不拦截，避免异常处理被 401/403 覆盖
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 匿名可访问：登录入口、SSO 公开配置（登录页展示用，不含密钥）、
                        // 健康检查与指标（部署层限制为内网/回环）、OpenAPI 文档
                        .requestMatchers("/api/auth/login", "/api/auth/sso/config",
                                "/actuator/health", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 开放 API（Batch 4a）：不依赖会话，由 ApiKeyAuthFilter 校验 X-API-Key
                        .requestMatchers("/api/open/**").permitAll()
                        // CORS 预检
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 仅 ADMIN
                        .requestMatchers("/api/users/**", "/api/audit-logs/**").hasRole("ADMIN")
                        // 异步任务队列（Batch 3b-1）：任务视图与重试均仅 ADMIN
                        .requestMatchers("/api/tasks/**").hasRole("ADMIN")
                        // 开放 API 密钥管理（Batch 4a）仅 ADMIN
                        .requestMatchers("/api/api-keys/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/dictionary/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/dictionary/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/dictionary/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/dictionary/**").hasRole("ADMIN")
                        // 系统设置：通知查看/测试与所有写操作为 ADMIN；复筛模型只读信息仍对登录用户开放
                        .requestMatchers(HttpMethod.GET, "/api/settings/notifications").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/settings/notifications/**").hasRole("ADMIN")
                        // 集成适配器状态（Batch 5，多租户/SSO/流式ASR/说话人分离）仅 ADMIN（无密钥）
                        .requestMatchers(HttpMethod.GET, "/api/settings/integrations").hasRole("ADMIN")
                        // AI 供应商管理（Batch 3a）：列表只回掩码，仍属敏感配置，仅 ADMIN 可读
                        .requestMatchers(HttpMethod.GET, "/api/settings/providers").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/settings/providers/**").hasRole("ADMIN")
                        // AI 成本看板（Batch 3a）仅 ADMIN
                        .requestMatchers(HttpMethod.GET, "/api/stats/ai-usage").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/settings/**").hasRole("ADMIN")
                        // 供应商删除仅 ADMIN（POST/PUT 规则不覆盖 DELETE）
                        .requestMatchers(HttpMethod.DELETE, "/api/settings/**").hasRole("ADMIN")
                        // 语料删除仅 ADMIN（文档 1.3：ADMIN only 包含「语料删除」）
                        .requestMatchers(HttpMethod.DELETE, "/api/corpus/**").hasRole("ADMIN")
                        // 录音指派 / 重开仅 ADMIN（复核工作流 1.6 的接口级要求更严格，按保守方案执行）
                        .requestMatchers(HttpMethod.PUT, "/api/recordings/*/assign").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/recordings/*/reopen").hasRole("ADMIN")
                        // ADMIN + REVIEWER
                        .requestMatchers("/api/reviews/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/recordings/upload",
                                "/api/recordings/*/retry",
                                "/api/recordings/*/delete",
                                "/api/recordings/*/claim").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/corpus/**").hasAnyRole("ADMIN", "REVIEWER")
                        // 知识库/SOP（Batch 4a）：读对所有登录用户开放，写限 ADMIN + REVIEWER
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.PUT, "/api/knowledge/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.DELETE, "/api/knowledge/**").hasAnyRole("ADMIN", "REVIEWER")
                        .requestMatchers(HttpMethod.DELETE, "/api/recordings/**").hasAnyRole("ADMIN", "REVIEWER")
                        // 其余 /api/** 与 /uploads/**（音频流）登录即可
                        .requestMatchers("/api/**", "/uploads/**").authenticated()
                        .anyRequest().authenticated());
        return http.build();
    }
}
