package com.echolint.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.echolint.asr.AsrSettingsService;
import com.echolint.config.AppProperties;
import com.echolint.config.SecurityConfig;
import com.echolint.controller.AsrSettingsController;
import com.echolint.controller.OpenApiController;
import com.echolint.controller.PipelineTaskController;
import com.echolint.controller.RecordingController;
import com.echolint.controller.SettingsController;
import com.echolint.controller.UserController;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.UserRole;
import com.echolint.entity.Recording;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * 安全矩阵集成测试（审计 P2-5）：把 {@link SecurityConfig} 的 URL 权限规则真正跑在过滤器链上， 断言匿名 / VIEWER / REVIEWER / ADMIN
 * 对关键端点的 401/403/200，并校验矩阵完整性 （Controller 新端点未登记到本测试的权限分类即失败）。
 *
 * <p>用 {@code @WebMvcTest} 切片：只装载被测 Controller + SecurityConfig，不触发 DataSource/JPA/Flyway； 会话认证通过预置
 * {@code SPRING_SECURITY_CONTEXT} + 会话纪元属性模拟（与真实登录写会话的方式一致）， 因此同时覆盖 P1-3 的 {@link
 * UserSessionGuardFilter}。
 */
@WebMvcTest(
        controllers = {
            RecordingController.class,
            UserController.class,
            PipelineTaskController.class,
            SettingsController.class,
            AsrSettingsController.class,
            OpenApiController.class
        })
@Import({
    SecurityConfig.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    SessionEpochService.class,
    SecurityMatrixTest.PropsConfig.class
})
class SecurityMatrixTest {

    @TestConfiguration
    static class PropsConfig {
        @Bean
        AppProperties appProperties() {
            return new AppProperties();
        }
    }

    @MockBean private com.echolint.service.RecordingService recordingService;
    @MockBean private com.echolint.service.ReviewService reviewService;
    @MockBean private com.echolint.service.UserService userService;
    @MockBean private com.echolint.service.PipelineTaskService pipelineTaskService;
    @MockBean private com.echolint.service.AuditService auditService;
    @MockBean private com.echolint.service.ScreenModelService screenModelService;
    @MockBean private com.echolint.service.ProviderAdminService providerAdminService;
    @MockBean private com.echolint.service.ModelEvaluationService modelEvaluationService;
    @MockBean private com.echolint.service.NotificationService notificationService;
    @MockBean private com.echolint.service.IntegrationStatusService integrationStatusService;
    @MockBean private AsrSettingsService asrSettingsService;
    @MockBean private com.echolint.service.ApiKeyService apiKeyService;
    @MockBean private AppUserDetailsService appUserDetailsService;

    @Autowired private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(springSecurityFilterChain)
                        .build();
        when(recordingService.query(any(), any())).thenReturn(Page.empty());
        when(recordingService.aiInvocations(1L)).thenReturn(List.of());
        when(userService.list()).thenReturn(List.of());
        when(userService.menuOptions()).thenReturn(List.of());
        when(pipelineTaskService.query(any(), any(), any())).thenReturn(Page.empty());
        when(providerAdminService.list()).thenReturn(List.of());
    }

    private MockHttpSession session(UserRole role) {
        String username = "user-" + role.name().toLowerCase();
        AppUserDetails details = new AppUserDetails(1L, username, "hash", username, role, true);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        session.setAttribute(SessionEpochService.SESSION_ATTRIBUTE, 0L);
        return session;
    }

    // ---------------------------------------------------------------- 关键端点角色矩阵

    @Test
    void anonymousIsRejectedOnProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/api/recordings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void recordingsListIsReadableByEveryLoggedInRole() throws Exception {
        for (UserRole role : List.of(UserRole.VIEWER, UserRole.REVIEWER, UserRole.ADMIN)) {
            mockMvc.perform(get("/api/recordings").session(session(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void recordingAiInvocationsIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/recordings/1/ai-invocations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/recordings/1/ai-invocations").session(session(UserRole.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/recordings/1/ai-invocations").session(session(UserRole.REVIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/recordings/1/ai-invocations").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void userManagementIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/users").session(session(UserRole.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").session(session(UserRole.REVIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void menuOptionsIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/users/menu-options")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/menu-options").session(session(UserRole.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/menu-options").session(session(UserRole.REVIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/menu-options").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void taskQueueIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/tasks").session(session(UserRole.REVIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tasks").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void providersSettingsIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/settings/providers").session(session(UserRole.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/settings/providers").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void asrSettingsIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/settings/asr")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/settings/asr").session(session(UserRole.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/settings/asr").session(session(UserRole.REVIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/settings/asr").session(session(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void asrSettingsWritesAreAdminOnly() throws Exception {
        when(asrSettingsService.update(any())).thenReturn(asrView());

        mockMvc.perform(
                        put("/api/settings/asr")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.VIEWER, UserRole.REVIEWER)) {
            mockMvc.perform(
                            put("/api/settings/asr")
                                    .session(session(role))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(
                            post("/api/settings/asr/test")
                                    .session(session(role))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(
                        put("/api/settings/asr")
                                .session(session(UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/settings/asr/test")
                                .session(session(UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk());
    }

    /** ASR 设置视图桩：PUT 成功后 Controller 会读视图字段写审计，字段不能为 null */
    private AsrSettingsService.View asrView() {
        return new AsrSettingsService.View(
                "local",
                600,
                new AsrSettingsService.LocalView(
                        "本地 whisper.cpp",
                        true,
                        "http://127.0.0.1:9900",
                        "whisper-1",
                        "/v1/audio/transcriptions",
                        true,
                        "****ocal"),
                new AsrSettingsService.DashScopeView(
                        "阿里云百炼",
                        false,
                        "https://dashscope.aliyuncs.com",
                        "qwen-audio-3.1-asr-flash-filetrans",
                        List.of(),
                        "zh",
                        false,
                        null));
    }

    @Test
    void openApiRequiresApiKeyAndAcceptsValidKey() throws Exception {
        mockMvc.perform(get("/api/open/recordings/1")).andExpect(status().isUnauthorized());

        Recording recording = new Recording();
        recording.setId(1L);
        recording.setFileName("call.mp3");
        recording.setFilePath("stored.mp3");
        recording.setFileSize(100L);
        recording.setStatus(RecordingStatus.PENDING);
        when(apiKeyService.authenticate("elk_test"))
                .thenReturn(
                        Optional.of(
                                new com.echolint.service.ApiKeyService.ApiKeyContext(
                                        1L, "ci", "elk_")));
        when(recordingService.get(1L)).thenReturn(recording);

        mockMvc.perform(get("/api/open/recordings/1").header(ApiKeyAuthFilter.HEADER, "elk_test"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 矩阵完整性

    /** SecurityConfig 中「除兜底 authenticated() 外」的显式规则（method + ant pattern）。 */
    private static final List<Rule> EXPLICIT_RULES =
            List.of(
                    new Rule("ANY", "/api/users/**"),
                    new Rule("ANY", "/api/audit-logs/**"),
                    new Rule("ANY", "/api/tasks/**"),
                    new Rule("ANY", "/api/api-keys/**"),
                    new Rule("POST", "/api/dictionary/**"),
                    new Rule("PUT", "/api/dictionary/**"),
                    new Rule("PATCH", "/api/dictionary/**"),
                    new Rule("DELETE", "/api/dictionary/**"),
                    new Rule("GET", "/api/settings/notifications"),
                    new Rule("GET", "/api/settings/notifications/**"),
                    new Rule("GET", "/api/settings/integrations"),
                    new Rule("GET", "/api/settings/providers"),
                    new Rule("GET", "/api/settings/providers/**"),
                    new Rule("GET", "/api/settings/rag"),
                    new Rule("GET", "/api/settings/asr"),
                    new Rule("GET", "/api/stats/ai-usage"),
                    new Rule("ANY", "/api/ai-invocations"),
                    new Rule("ANY", "/api/ai-invocations/**"),
                    new Rule("GET", "/api/recordings/*/ai-invocations"),
                    new Rule("PUT", "/api/settings/**"),
                    new Rule("POST", "/api/settings/**"),
                    new Rule("DELETE", "/api/settings/**"),
                    new Rule("DELETE", "/api/corpus/**"),
                    new Rule("PUT", "/api/recordings/*/assign"),
                    new Rule("POST", "/api/recordings/*/reopen"),
                    new Rule("ANY", "/api/reviews/**"),
                    new Rule("POST", "/api/recordings/upload"),
                    new Rule("POST", "/api/recordings/*/retry"),
                    new Rule("POST", "/api/recordings/*/delete"),
                    new Rule("POST", "/api/recordings/*/claim"),
                    new Rule("POST", "/api/corpus/**"),
                    new Rule("POST", "/api/knowledge/**"),
                    new Rule("PUT", "/api/knowledge/**"),
                    new Rule("DELETE", "/api/knowledge/**"),
                    new Rule("GET", "/api/knowledge/*/chunks"),
                    new Rule("DELETE", "/api/recordings/**"),
                    new Rule("ANY", "/api/auth/login"),
                    new Rule("ANY", "/api/auth/sso/config"),
                    new Rule("ANY", "/api/open/**"));

    /**
     * 有意落在兜底 {@code .authenticated()}（登录即可）上的端点：新增端点必须显式登记到这里 或 EXPLICIT_RULES，否则本测试失败，强制作者做权限决策。
     */
    private static final Set<String> FALLBACK_AUTHENTICATED =
            Set.of(
                    "GET /api/recordings",
                    "GET /api/recordings/*",
                    "GET /api/recordings/*/logs",
                    "GET /api/recordings/*/reviews",
                    "GET /api/auth/me",
                    "POST /api/auth/logout",
                    "POST /api/auth/password",
                    "GET /api/corpus",
                    "GET /api/corpus/export",
                    "GET /api/dictionary",
                    "GET /api/dictionary/export",
                    "GET /api/knowledge",
                    "GET /api/knowledge/*",
                    "GET /api/reports/recordings.csv",
                    "GET /api/reports/violations.csv",
                    "GET /api/reports/reviews.csv",
                    "GET /api/settings/screen-model",
                    "GET /api/stats/overview",
                    "GET /api/stats/report");

    @Test
    void everyControllerEndpointIsClassifiedInSecurityMatrix() throws Exception {
        Set<String> discovered = discoverEndpoints();
        List<String> unclassified =
                discovered.stream().filter(endpoint -> !isClassified(endpoint)).sorted().toList();
        assertTrue(
                unclassified.isEmpty(),
                "以下端点未登记权限分类（在 SecurityMatrixTest 的 EXPLICIT_RULES 或 FALLBACK_AUTHENTICATED 中补充）："
                        + unclassified);
    }

    private boolean isClassified(String endpoint) {
        if (FALLBACK_AUTHENTICATED.contains(endpoint)) {
            return true;
        }
        int idx = endpoint.indexOf(' ');
        String method = endpoint.substring(0, idx);
        String path = endpoint.substring(idx + 1);
        AntPathMatcher matcher = new AntPathMatcher();
        return EXPLICIT_RULES.stream()
                .anyMatch(
                        rule ->
                                ("ANY".equals(rule.method()) || rule.method().equals(method))
                                        && matcher.match(rule.path(), path));
    }

    /** 反射枚举所有 Controller 的映射，{@code {var}} 归一化为 {@code *}。 */
    private Set<String> discoverEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        Set<String> endpoints = new LinkedHashSet<>();
        for (BeanDefinition candidate :
                scanner.findCandidateComponents("com.echolint.controller")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName(), false, loader);
            List<String> bases = new ArrayList<>();
            RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
            if (classMapping == null || classMapping.value().length == 0) {
                bases.add("");
            } else {
                for (String base : classMapping.value()) {
                    bases.add(base);
                }
            }
            for (Method method : controller.getDeclaredMethods()) {
                for (Mapped mapped : mappingsOf(method)) {
                    for (String base : bases) {
                        for (String sub : mapped.paths()) {
                            endpoints.add(mapped.method() + " " + normalize(base + sub));
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    private List<Mapped> mappingsOf(Method method) {
        List<Mapped> result = new ArrayList<>();
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            result.add(new Mapped("GET", paths(get.value(), get.path())));
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            result.add(new Mapped("POST", paths(post.value(), post.path())));
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            result.add(new Mapped("PUT", paths(put.value(), put.path())));
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            result.add(new Mapped("DELETE", paths(delete.value(), delete.path())));
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            result.add(new Mapped("PATCH", paths(patch.value(), patch.path())));
        }
        return result;
    }

    private static List<String> paths(String[] values, String[] paths) {
        List<String> merged = new ArrayList<>();
        for (String value : values) {
            merged.add(value);
        }
        for (String path : paths) {
            merged.add(path);
        }
        if (merged.isEmpty()) {
            merged.add("");
        }
        return merged;
    }

    private static String normalize(String path) {
        String normalized = path.isBlank() ? "/" : (path.startsWith("/") ? path : "/" + path);
        normalized = normalized.replaceAll("\\{[^}]+}", "*");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record Rule(String method, String path) {}

    private record Mapped(String method, List<String> paths) {}
}
