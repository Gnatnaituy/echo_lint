package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 复筛模型注册表：解析当前生效的供应商/模型、运行时切换（落库）、连通性测试。
 *
 * <p>复筛（{@link SemanticScreeningService}）与词挖掘（{@link WordMiningService}）共用同一份选择，
 * 因此「换个模型试试」只需在系统设置页点一下，无需重启。</p>
 *
 * <p>Batch 3a 起供应商来源改为 {@link ProviderRegistry}：数据库优先、yml 兜底；
 * 每次解析时解密 API Key 并按需构造 WebClient，供应商 CRUD 后立即生效（无需重启）。
 * 当前选择仍存放在 app_settings（screen.provider / screen.model）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenModelService {

    /** 运行时选择持久化键 */
    static final String KEY_PROVIDER = "screen.provider";
    static final String KEY_MODEL = "screen.model";

    private final ScreenModelProperties props;
    private final AppSettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final ProviderRegistry providers;

    // ------------------------------------------------------------------ 解析

    /**
     * 当前生效的模型（未做密钥校验，供设置页展示）。
     *
     * <p>已保存的供应商被删除或停用时回退到默认供应商（yml default-provider），
     * 其旧模型名一并作废，避免拿旧模型名去打新供应商。</p>
     */
    public ActiveModel active() {
        String storedProvider = read(KEY_PROVIDER).orElse(null);
        String storedModel = read(KEY_MODEL).orElse(null);

        Optional<ResolvedProvider> found = providers.find(storedProvider).filter(ResolvedProvider::enabled);
        if (StringUtils.hasText(storedProvider) && found.isEmpty()) {
            log.warn("已保存的复筛供应商 {} 不存在或已停用，回退到默认供应商", storedProvider);
        }
        ResolvedProvider provider = found
                .or(this::defaultProvider)
                .orElseThrow(() -> new BizException(
                        "没有可用的 AI 复筛供应商（配置或系统设置中的供应商均已停用/缺失）",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        String model = found.isPresent() ? storedModel : null;
        return toActive(provider, model);
    }

    /** 默认供应商：配置的 default-provider（需启用），否则第一个启用的供应商 */
    private Optional<ResolvedProvider> defaultProvider() {
        Optional<ResolvedProvider> configured = providers.find(props.getDefaultProvider())
                .filter(ResolvedProvider::enabled);
        return configured.or(() -> providers.all().stream()
                .filter(ResolvedProvider::enabled)
                .findFirst());
    }

    /**
     * 语义复筛选型（Batch 3b-2）：开启 A/B 且备用供应商可用时，按 percent 概率走 B 组；
     * 否则走当前生效模型（A 组）。只用于语义复筛，词挖掘仍走 {@link #active()}。
     *
     * @return 实际使用的模型 + 分组标记（"A"/"B"）
     */
    public ScreeningModel activeForScreening() {
        ActiveModel primary = active();
        Optional<ActiveModel> alternate = alternateForScreening();
        if (alternate.isEmpty()) {
            return new ScreeningModel(primary, "A");
        }
        ScreenModelProperties.Ab ab = props.getAb();
        int percent = ab == null ? 0 : ab.effectivePercent();
        if (percent <= 0) {
            return new ScreeningModel(primary, "A");
        }
        boolean useB = percent >= 100 || Math.random() * 100 < percent;
        if (!useB) {
            return new ScreeningModel(primary, "A");
        }
        log.debug("语义复筛 A/B 分流：本次走 B 组 {}（配置 {}%）", alternate.get().display(), percent);
        return new ScreeningModel(alternate.get(), "B");
    }

    /**
     * 解析 A/B 备用模型：供应商必须存在、启用且密钥就绪，模型名可解析；任一条件不满足回退 A 组。
     * 配置错误只记 DEBUG，避免每条录音刷日志（启动日志会给出 A/B 配置状态）。
     */
    private Optional<ActiveModel> alternateForScreening() {
        ScreenModelProperties.Ab ab = props.getAb();
        if (ab == null || !ab.isEnabled() || !StringUtils.hasText(ab.getProviderId())) {
            return Optional.empty();
        }
        Optional<ResolvedProvider> found = providers.find(ab.getProviderId());
        if (found.isEmpty() || !found.get().enabled()) {
            log.debug("A/B 备用供应商 {} 不存在或已停用，语义复筛回退 A 组", ab.getProviderId());
            return Optional.empty();
        }
        ResolvedProvider provider = found.get();
        if (!provider.apiKeyConfigured()) {
            log.debug("A/B 备用供应商 {} 未配置 API Key，语义复筛回退 A 组", ab.getProviderId());
            return Optional.empty();
        }
        if (!StringUtils.hasText(provider.resolvedModel(ab.getModel()))) {
            log.debug("A/B 备用供应商 {} 无法解析模型名，语义复筛回退 A 组", ab.getProviderId());
            return Optional.empty();
        }
        return Optional.of(toActive(provider, ab.getModel()));
    }

    /**
     * 按供应商/模型解析调用句柄（供模型评测等显式指定模型的场景）。
     * 不校验 enabled / 密钥：评测可以选择停用中的候选模型，密钥就绪由调用方用 {@link #requireUsable} 校验。
     */
    public ActiveModel resolveModel(String providerId, String requestedModel) {
        ResolvedProvider provider = providers.find(providerId)
                .orElseThrow(() -> new BizException("未知的模型供应商: " + providerId));
        return toActive(provider, requestedModel);
    }

    /**
     * 当前生效的模型，且要求密钥就绪（供真正发起调用前校验）
     */
    public ActiveModel requireActive() {
        ActiveModel active = active();
        requireUsable(active);
        return active;
    }

    /**
     * 校验给定模型是否可调用。单独暴露是为了让调用方先组装请求体（用于留痕）再校验，
     * 这样即使因缺密钥失败，留痕里也能看到「本该发出去什么」。
     */
    public void requireUsable(ActiveModel model) {
        if (model != null && model.apiKeyConfigured()) {
            return;
        }
        String hint = model == null ? "" : providers.find(model.providerId())
                .map(ResolvedProvider::apiKeyHint)
                .orElse("");
        throw new BizException("当前复筛模型「" + (model == null ? "未知" : model.display()) + "」未配置 API Key："
                + hint + "；或在「系统设置」中切换到其它模型",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ActiveModel toActive(ResolvedProvider provider, String requestedModel) {
        return new ActiveModel(
                provider.providerKey(),
                provider.displayLabel(),
                ProviderRegistry.trimTrailingSlash(provider.baseUrl()),
                provider.resolvedModel(requestedModel),
                provider.chatPath(),
                provider.jsonMode(),
                provider.apiKeyConfigured(),
                provider.maxTokens(),
                provider.extraBody() == null ? Map.of() : provider.extraBody(),
                props.getRequestTimeoutSeconds(),
                buildClient(provider),
                provider.inputPricePer1k(),
                provider.outputPricePer1k());
    }

    /**
     * 按调用构造 WebClient：供应商 CRUD 后无需重建缓存，密钥在解析时解密。
     */
    private WebClient buildClient(ResolvedProvider provider) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(ProviderRegistry.trimTrailingSlash(provider.baseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (provider.apiKeyConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    /** 实际请求的完整端点（不含密钥），用于留痕与排查 */
    static String endpointOf(ActiveModel model) {
        if (model == null) {
            return null;
        }
        String base = model.baseUrl() == null ? "" : model.baseUrl();
        String path = model.chatPath() == null ? "" : model.chatPath();
        return ProviderRegistry.trimTrailingSlash(base) + path;
    }

    // ------------------------------------------------------------------ 切换

    /**
     * 运行时切换复筛模型（立即生效，落库持久化）。未知/已停用的供应商一律拒绝。
     */
    public View switchTo(String providerId, String requestedModel) {
        ResolvedProvider provider = providers.find(providerId)
                .orElseThrow(() -> new BizException("未知的模型供应商: " + providerId));
        if (!provider.enabled()) {
            throw new BizException("模型供应商已停用，请先在「AI 供应商」中启用: " + provider.displayLabel(),
                    HttpStatus.CONFLICT);
        }
        String model = provider.resolvedModel(requestedModel);
        if (!StringUtils.hasText(model)) {
            throw new BizException("请指定模型名称（供应商 " + provider.displayLabel() + " 未配置 default-model）");
        }
        save(KEY_PROVIDER, provider.providerKey());
        save(KEY_MODEL, model);
        log.info("AI 复筛模型已切换为 {} · {}{}", provider.displayLabel(), model,
                provider.apiKeyConfigured() ? "" : "（注意：该供应商尚未配置 API Key）");
        return snapshot();
    }

    // ------------------------------------------------------------------ 视图

    /**
     * 设置页所需的完整视图：当前选择 + 全部候选供应商（DB + yml 兜底）。
     */
    public View snapshot() {
        ActiveModel active = active();
        boolean fromDb = providers.find(read(KEY_PROVIDER).orElse(null)).isPresent();
        List<ResolvedProvider> all = providers.all();
        List<ProviderView> views = new ArrayList<>();
        for (ResolvedProvider p : all) {
            boolean isActive = p.providerKey().equalsIgnoreCase(active.providerId());
            views.add(new ProviderView(
                    p.providerKey(),
                    p.displayLabel(),
                    ProviderRegistry.trimTrailingSlash(p.baseUrl()),
                    p.chatPath(),
                    p.resolvedModel(null),
                    p.models() == null ? List.of() : List.copyOf(p.models()),
                    p.jsonMode(),
                    p.apiKeyConfigured(),
                    p.apiKeyEnv(),
                    isActive ? active.model() : p.resolvedModel(null),
                    isActive));
        }
        return new View(active.providerId(), active.providerLabel(), active.model(),
                active.apiKeyConfigured(), fromDb ? "DATABASE" : "DEFAULT", props.getFewShotCount(), views,
                abView());
    }

    /** A/B 配置（只读展示；前端据此提示当前分流策略） */
    private AbView abView() {
        ScreenModelProperties.Ab ab = props.getAb();
        if (ab == null) {
            return new AbView(false, 0, null, null);
        }
        return new AbView(ab.isEnabled(), ab.effectivePercent(), ab.getProviderId(), ab.getModel());
    }

    // ------------------------------------------------------------------ 连通性测试

    /**
     * 用真实请求探活：确认 base-url / api-key / 模型名三者可用，并顺带验证 JSON 模式。
     * 失败不抛异常，通过 ok=false + message 返回，便于界面直接展示。
     */
    public TestResult test(String providerId, String requestedModel) {
        ResolvedProvider provider = providers.find(providerId)
                .orElseThrow(() -> new BizException("未知的模型供应商: " + providerId));
        String model = provider.resolvedModel(requestedModel);

        if (!provider.apiKeyConfigured()) {
            return new TestResult(false, provider.providerKey(), provider.displayLabel(), model, 0, "",
                    "未配置 API Key：" + provider.apiKeyHint());
        }
        if (!StringUtils.hasText(model)) {
            return new TestResult(false, provider.providerKey(), provider.displayLabel(), "", 0, "",
                    "未配置默认模型，请指定 model");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (provider.extraBody() != null) {
            body.putAll(provider.extraBody());
        }
        body.put("model", model);
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "你是连通性测试助手，只输出 JSON，不要输出其他内容。"),
                Map.of("role", "user", "content", "请只回复一个 JSON 对象：{\"ok\": true}")));
        if (provider.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (provider.maxTokens() != null && provider.maxTokens() > 0) {
            body.put("max_tokens", provider.maxTokens());
        }

        long start = System.currentTimeMillis();
        try {
            JsonNode resp = buildClient(provider).post()
                    .uri(provider.chatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr -> cr.bodyToMono(String.class).map(err ->
                            new BizException("HTTP " + cr.statusCode().value() + ": "
                                    + extractErrorMessage(objectMapper, err))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(Math.min(props.getRequestTimeoutSeconds(), 60)));
            long cost = System.currentTimeMillis() - start;
            if (resp == null) {
                return new TestResult(false, provider.providerKey(), provider.displayLabel(), model, cost, "",
                        "响应为空");
            }
            String reply = contentOf(resp);
            return new TestResult(true, provider.providerKey(), provider.displayLabel(), model, cost,
                    truncate(reply, 200), "连通正常");
        } catch (BizException e) {
            return new TestResult(false, provider.providerKey(), provider.displayLabel(), model,
                    System.currentTimeMillis() - start, "", e.getMessage());
        } catch (Exception e) {
            return new TestResult(false, provider.providerKey(), provider.displayLabel(), model,
                    System.currentTimeMillis() - start, "", "调用异常: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 持久化

    private Optional<String> read(String key) {
        return settingRepository.findById(key)
                .map(AppSetting::getSettingValue)
                .filter(StringUtils::hasText);
    }

    private void save(String key, String value) {
        settingRepository.save(AppSetting.of(key, value));
    }

    // ------------------------------------------------------------------ 共用工具

    /** 从 OpenAI 兼容响应中取 choices[0].message.content */
    static String contentOf(JsonNode resp) {
        return resp.path("choices").path(0).path("message").path("content").asText("");
    }

    /** 从 OpenAI 兼容错误体中取 error.message，取不到就截断原文 */
    static String extractErrorMessage(ObjectMapper mapper, String errorBody) {
        try {
            String msg = mapper.readTree(errorBody).path("error").path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return truncate(String.valueOf(errorBody), 300);
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ DTO

    /**
     * 当前生效的模型及其调用句柄（单价用于成本快照，Batch 3a）
     */
    public record ActiveModel(
            String providerId,
            String providerLabel,
            String baseUrl,
            String model,
            String chatPath,
            boolean jsonMode,
            boolean apiKeyConfigured,
            Integer maxTokens,
            Map<String, Object> extraBody,
            long timeoutSeconds,
            WebClient client,
            BigDecimal inputPricePer1k,
            BigDecimal outputPricePer1k) {

        public String display() {
            return providerLabel + " · " + model;
        }
    }

    /**
     * 单个候选供应商（不下发 api-key，只下发是否已配置）
     */
    public record ProviderView(
            String id,
            String label,
            String baseUrl,
            String chatPath,
            String defaultModel,
            List<String> models,
            boolean jsonMode,
            boolean apiKeyConfigured,
            String apiKeyEnv,
            String model,
            boolean active) {
    }

    /**
     * 语义复筛选型结果：实际使用的模型 + A/B 分组（"A"/"B"）
     */
    public record ScreeningModel(ActiveModel model, String variant) {
    }

    /**
     * 系统设置页视图
     */
    public record View(
            String providerId,
            String providerLabel,
            String model,
            boolean apiKeyConfigured,
            String source,
            int fewShotCount,
            List<ProviderView> providers,
            AbView ab) {

        /** 兼容既有调用方：不含 A/B 配置 */
        public View(String providerId, String providerLabel, String model, boolean apiKeyConfigured,
                    String source, int fewShotCount, List<ProviderView> providers) {
            this(providerId, providerLabel, model, apiKeyConfigured, source, fewShotCount, providers, null);
        }
    }

    /**
     * A/B 分流配置（只读下发；配置来自 application.yml，运行时不可改）
     */
    public record AbView(
            boolean enabled,
            int percent,
            String providerId,
            String model) {
    }

    /**
     * 连通性测试结果
     */
    public record TestResult(
            boolean ok,
            String providerId,
            String providerLabel,
            String model,
            long latencyMs,
            String reply,
            String message) {
    }
}
