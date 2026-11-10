package com.echolint.asr;

import com.echolint.config.SecretCipher;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 语音转写运行时设置（provider 切换 + 本地/百炼配置）。
 *
 * <p>沿用 AI 复筛模型 / RAG 设置的范式：默认值来自 {@code application.yml}（{@link AsrProperties}）， 管理员在「系统设置 →
 * 语音转写」页保存后写入 {@code app_settings}，<b>立即生效、重启保持</b>。 非敏感项（base-url / model / language-hints /
 * timeout）DB 优先、env 兜底； <b>API Key 只读数据库</b>（{@link SecretCipher} 加密落库，界面只回掩码）——环境变量仅由 {@link
 * AsrKeyBootstrapRunner} 在首次启动时作为引导值加密写入一次，之后不再读取。
 *
 * <p>契约见 docs/asr-and-settings-refactor.md §2 与 §6.2：
 *
 * <ul>
 *   <li>{@code asr.provider} 未落库时默认<b>本地 whisper.cpp</b>：百炼 Key 就绪不会隐式切云， 只有显式提交 {@code
 *       activeProvider} 才会切换（PUT 未带 activeProvider 时把当前生效 provider 显式落库，避免就绪状态变化导致漂移）；
 *   <li>PUT 的空 {@code apiKey} 表示保持原值；切换目标未就绪返回 400（提示「请在设置页录入 API Key」）；
 *   <li>解密失败按「未配置」处理并 WARN，不影响启动。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrSettingsService {

    static final String KEY_PROVIDER = "asr.provider";
    static final String KEY_LOCAL_BASE_URL = "asr.local.base-url";
    static final String KEY_LOCAL_API_KEY = "asr.local.api-key";
    static final String KEY_LOCAL_MODEL = "asr.local.model";
    static final String KEY_DASHSCOPE_BASE_URL = "asr.dashscope.base-url";
    static final String KEY_DASHSCOPE_API_KEY = "asr.dashscope.api-key";
    static final String KEY_DASHSCOPE_MODEL = "asr.dashscope.model";
    static final String KEY_DASHSCOPE_LANGUAGE_HINTS = "asr.dashscope.language-hints";
    static final String KEY_TIMEOUT_SECONDS = "asr.timeout-seconds";

    /** app_settings.setting_value 为 varchar(255)，AES-GCM 密文 Base64 后约 4/3 膨胀：明文上限取 160。 */
    private static final int MAX_API_KEY_LENGTH = 160;

    private static final int MAX_URL_LENGTH = 255;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_LANGUAGE_HINTS_LENGTH = 200;

    private final AppSettingRepository settings;
    private final AsrProperties properties;
    private final SecretCipher cipher;
    private final List<AsrProvider> providers;

    // ------------------------------------------------------------------ DTO

    /** PUT 请求体（字段为 null 表示「不修改」；apiKey 空串表示保持原密钥） */
    public record UpdateRequest(
            String activeProvider,
            Long timeoutSeconds,
            LocalInput local,
            DashScopeInput dashscope) {}

    public record LocalInput(String baseUrl, String model, String apiKey) {}

    public record DashScopeInput(
            String baseUrl, String model, String languageHints, String apiKey) {}

    /** 本地 provider 视图（§6.2：不含 apiKeySource / envHints） */
    public record LocalView(
            String label,
            boolean ready,
            String baseUrl,
            String model,
            String audioPath,
            boolean apiKeyConfigured,
            String apiKeyMasked) {}

    /** 百炼 provider 视图（§6.2：不含 apiKeySource / envHints） */
    public record DashScopeView(
            String label,
            boolean ready,
            String baseUrl,
            String model,
            List<String> models,
            String languageHints,
            boolean apiKeyConfigured,
            String apiKeyMasked) {}

    /** GET/PUT 返回的完整视图（不含明文/密文密钥） */
    public record View(
            String activeProvider, int timeoutSeconds, LocalView local, DashScopeView dashscope) {}

    /** 连通性测试结果（失败也返回 200 + ok=false，便于界面直接展示原因） */
    public record TestResult(
            boolean ok, String provider, String model, long latencyMs, String message) {}

    /** 当前生效配置（apiKey 已解密，只在进程内使用，绝不写日志/响应） */
    public record Resolved(AsrProviderType type, AsrProvider.Config config) {

        public boolean ready() {
            return config.ready();
        }

        public String unreadyMessage() {
            return "语音转写未就绪（" + type.label() + "）：" + AsrProvider.missingDescription(config);
        }
    }

    private record LocalValues(String baseUrl, String model, String apiKey) {}

    private record DashScopeValues(
            String baseUrl, String model, String languageHints, String apiKey) {}

    // ------------------------------------------------------------------ 读取

    /** 当前生效配置：TranscriptionService 每次转写都按此解析并分发到对应 provider。 */
    public Resolved current() {
        AsrProvider.Config local = localSettings();
        AsrProvider.Config dashscope = dashscopeSettings();
        AsrProviderType type = activeType(local, dashscope);
        return new Resolved(type, type == AsrProviderType.LOCAL ? local : dashscope);
    }

    /** 设置页视图：当前生效 provider + 超时 + 两套配置（密钥只回是否已配置与掩码） */
    public View view() {
        AsrProvider.Config local = localSettings();
        AsrProvider.Config dashscope = dashscopeSettings();
        AsrProviderType active = activeType(local, dashscope);
        return new View(
                active.value(),
                timeoutSeconds(),
                new LocalView(
                        AsrProviderType.LOCAL.label(),
                        local.ready(),
                        local.baseUrl(),
                        local.model(),
                        local.audioPath(),
                        StringUtils.hasText(local.apiKey()),
                        cipher.mask(local.apiKey())),
                new DashScopeView(
                        AsrProviderType.DASHSCOPE.label(),
                        dashscope.ready(),
                        dashscope.baseUrl(),
                        dashscope.model(),
                        dashscopeModels(dashscope.model()),
                        String.join(",", dashscope.languageHints()),
                        StringUtils.hasText(dashscope.apiKey()),
                        cipher.mask(dashscope.apiKey())));
    }

    // ------------------------------------------------------------------ 更新/切换

    /** 保存配置（可选同时切换 provider）。切换前先用「本次请求生效后」的配置校验目标是否就绪， 未就绪直接 400，且不落库任何改动。 */
    @Transactional
    public View update(UpdateRequest request) {
        UpdateRequest req = request == null ? new UpdateRequest(null, null, null, null) : request;
        AsrProviderType target = parseTarget(req.activeProvider());
        Integer newTimeout =
                req.timeoutSeconds() == null ? null : clampRequestedTimeout(req.timeoutSeconds());

        LocalValues localValues = validateLocal(req.local());
        DashScopeValues dashValues = validateDashscope(req.dashscope());

        if (target != null) {
            AsrProvider.Config prospective =
                    target == AsrProviderType.LOCAL
                            ? prospectiveLocal(localValues, newTimeout)
                            : prospectiveDashscope(dashValues, newTimeout);
            if (!prospective.ready()) {
                throw new BizException(
                        "无法切换到"
                                + target.label()
                                + "："
                                + AsrProvider.missingDescription(prospective));
            }
        }

        applyLocal(localValues);
        applyDashscope(dashValues);
        if (newTimeout != null) {
            save(KEY_TIMEOUT_SECONDS, Integer.toString(newTimeout));
        }
        if (target != null) {
            save(KEY_PROVIDER, target.value());
            log.info("语音转写 provider 已切换为 {}（{}）", target.label(), target.value());
        } else {
            // 未显式切换时把「当前生效 provider」落库，避免后续就绪状态变化导致 provider 漂移
            AsrProviderType kept = current().type();
            save(KEY_PROVIDER, kept.value());
            log.info("语音转写配置已更新（provider 保持 {}）", kept.value());
        }
        return view();
    }

    /** 连通性测试：{@code requested} 为空表示测当前生效 provider。 本地探活 / 百炼只取上传凭证，均不产生识别费用；失败不抛异常。 */
    public TestResult test(AsrProviderType requested) {
        AsrProvider.Config local = localSettings();
        AsrProvider.Config dashscope = dashscopeSettings();
        AsrProviderType target = requested == null ? activeType(local, dashscope) : requested;
        AsrProvider.Config config = target == AsrProviderType.LOCAL ? local : dashscope;
        String model = config.model() == null ? "" : config.model();
        if (!config.ready()) {
            return new TestResult(
                    false,
                    target.value(),
                    model,
                    0,
                    "语音转写未就绪：" + AsrProvider.missingDescription(config));
        }
        long start = System.currentTimeMillis();
        try {
            AsrProvider.ConnectResult result = providerOf(target).test(config);
            return new TestResult(
                    result.ok(),
                    target.value(),
                    model,
                    System.currentTimeMillis() - start,
                    result.message());
        } catch (Exception e) {
            return new TestResult(
                    false,
                    target.value(),
                    model,
                    System.currentTimeMillis() - start,
                    "调用异常: " + AsrProvider.errorMessage(e));
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 当前生效 provider：DB 已保存的合法值优先。
     *
     * <p>未保存（或保存值非法）时默认<b>本地 whisper.cpp</b>——不能因为百炼 Key 已存在就隐式切云； 切换必须走 PUT 的显式 {@code
     * activeProvider}。已保存百炼但当前未就绪（密钥被移除/解密失败） 时回退本地并 WARN，避免整条流水线失败。
     */
    private AsrProviderType activeType(AsrProvider.Config local, AsrProvider.Config dashscope) {
        Optional<String> rawProvider = read(KEY_PROVIDER);
        AsrProviderType stored = AsrProviderType.fromValue(rawProvider.orElse(null));
        if (stored == AsrProviderType.DASHSCOPE) {
            if (dashscope.ready()) {
                return AsrProviderType.DASHSCOPE;
            }
            log.warn("已保存的转写 provider=dashscope 但百炼当前未就绪，本次回退本地 whisper.cpp");
            return AsrProviderType.LOCAL;
        }
        if (stored == AsrProviderType.LOCAL) {
            return AsrProviderType.LOCAL;
        }
        if (rawProvider.isPresent()) {
            log.warn("已保存的转写 provider 非法（{}），按未设置处理", rawProvider.get());
        }
        return AsrProviderType.LOCAL;
    }

    private AsrProvider.Config localSettings() {
        String baseUrl =
                firstText(
                        read(KEY_LOCAL_BASE_URL).orElse(null), properties.getLocal().getBaseUrl());
        String model =
                firstText(read(KEY_LOCAL_MODEL).orElse(null), properties.getLocal().getModel());
        String audioPath =
                firstText(properties.getLocal().getAudioPath(), "/v1/audio/transcriptions");
        return new AsrProvider.Config(
                baseUrl,
                readApiKey(KEY_LOCAL_API_KEY, AsrProviderType.LOCAL),
                model,
                audioPath,
                List.of(),
                timeoutSeconds());
    }

    private AsrProvider.Config dashscopeSettings() {
        String baseUrl =
                normalizeDashScopeBaseUrl(
                        firstText(
                                read(KEY_DASHSCOPE_BASE_URL).orElse(null),
                                properties.getDashscope().getBaseUrl()));
        String model =
                firstText(
                        read(KEY_DASHSCOPE_MODEL).orElse(null),
                        properties.getDashscope().getModel());
        String hints =
                firstText(
                        read(KEY_DASHSCOPE_LANGUAGE_HINTS).orElse(null),
                        properties.getDashscope().getLanguageHints());
        return new AsrProvider.Config(
                baseUrl,
                readApiKey(KEY_DASHSCOPE_API_KEY, AsrProviderType.DASHSCOPE),
                model,
                "",
                parseLanguageHints(hints),
                timeoutSeconds());
    }

    /**
     * 运行时密钥只读数据库（§6.2）。环境变量仅由 {@link AsrKeyBootstrapRunner} 首次启动时加密导入一次； 解密失败（APP_SECRET_KEY
     * 变更）按「未配置」处理并 WARN。
     */
    private String readApiKey(String settingKey, AsrProviderType type) {
        Optional<String> storedCipher = read(settingKey);
        if (storedCipher.isEmpty()) {
            return null;
        }
        try {
            String plaintext = cipher.decrypt(storedCipher.get());
            return StringUtils.hasText(plaintext) ? plaintext.trim() : null;
        } catch (Exception e) {
            log.warn("{} 的 API Key 解密失败（APP_SECRET_KEY 是否已变更？），按未配置处理", type.label());
            return null;
        }
    }

    private int timeoutSeconds() {
        Optional<String> stored = read(KEY_TIMEOUT_SECONDS);
        if (stored.isEmpty()) {
            return (int) properties.effectiveTimeoutSeconds();
        }
        try {
            return (int) AsrProperties.clampTimeoutSeconds(Long.parseLong(stored.get()));
        } catch (NumberFormatException e) {
            log.warn(
                    "已保存的转写超时配置非法（{}），回退默认 {}s",
                    stored.get(),
                    properties.effectiveTimeoutSeconds());
            return (int) properties.effectiveTimeoutSeconds();
        }
    }

    // ------------------------------------------------------------------ 写入

    private void applyLocal(LocalValues values) {
        if (values == null) {
            return;
        }
        if (values.baseUrl() != null) {
            save(KEY_LOCAL_BASE_URL, values.baseUrl());
        }
        if (values.model() != null) {
            save(KEY_LOCAL_MODEL, values.model());
        }
        if (values.apiKey() != null) {
            save(KEY_LOCAL_API_KEY, cipher.encrypt(values.apiKey()));
        }
    }

    private void applyDashscope(DashScopeValues values) {
        if (values == null) {
            return;
        }
        if (values.baseUrl() != null) {
            save(KEY_DASHSCOPE_BASE_URL, values.baseUrl());
        }
        if (values.model() != null) {
            save(KEY_DASHSCOPE_MODEL, values.model());
        }
        if (values.languageHints() != null) {
            // 落库统一为规范化形式（小写、去空白、去重），视图/接口口径一致
            save(
                    KEY_DASHSCOPE_LANGUAGE_HINTS,
                    String.join(",", parseLanguageHints(values.languageHints())));
        }
        if (values.apiKey() != null) {
            save(KEY_DASHSCOPE_API_KEY, cipher.encrypt(values.apiKey()));
        }
    }

    // ------------------------------------------------------------------ 入参校验/预演

    private static AsrProviderType parseTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        AsrProviderType type = AsrProviderType.fromValue(raw);
        if (type == null) {
            throw new BizException("未知的语音转写 provider: " + raw.trim() + "（可选 local / dashscope）");
        }
        return type;
    }

    private Integer clampRequestedTimeout(long requested) {
        long clamped = AsrProperties.clampTimeoutSeconds(requested);
        if (clamped != requested) {
            log.info(
                    "转写超时 {}s 超出 {}~{}s 范围，已钳制为 {}s",
                    requested,
                    AsrProperties.MIN_TIMEOUT_SECONDS,
                    AsrProperties.MAX_TIMEOUT_SECONDS,
                    clamped);
        }
        return (int) clamped;
    }

    private LocalValues validateLocal(LocalInput input) {
        if (input == null) {
            return null;
        }
        return new LocalValues(
                validateBaseUrl(input.baseUrl(), "本地 whisper.cpp 服务地址"),
                validateOptionalText(input.model(), "本地模型名", MAX_MODEL_LENGTH),
                validateApiKey(input.apiKey()));
    }

    private DashScopeValues validateDashscope(DashScopeInput input) {
        if (input == null) {
            return null;
        }
        return new DashScopeValues(
                normalizeDashScopeBaseUrl(validateBaseUrl(input.baseUrl(), "百炼服务地址")),
                validateOptionalText(input.model(), "百炼模型名", MAX_MODEL_LENGTH),
                validateOptionalText(input.languageHints(), "语言提示", MAX_LANGUAGE_HINTS_LENGTH),
                validateApiKey(input.apiKey()));
    }

    /**
     * 百炼 API 根固定是域名根（{@code https://{workspace}.cn-beijing.maas.aliyuncs.com}）。
     *
     * <p>用户容易把文档示例里的完整地址（带 {@code /api/v1} 甚至具体接口路径）粘贴进来，若原样拼接 会请求成 {@code /api/v1/api/v1/...} 返回
     * 404（2026-11-10 线上实测问题）。这里统一收敛为 {@code scheme://authority}；非标准 URL 只做去尾斜杠与 {@code /api/v1}
     * 后缀的字符串规整。
     */
    static String normalizeDashScopeBaseUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String value = raw.trim();
        try {
            URI uri = URI.create(value);
            if (StringUtils.hasText(uri.getScheme()) && StringUtils.hasText(uri.getAuthority())) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (IllegalArgumentException e) {
            log.debug("百炼服务地址无法按 URL 解析，改为字符串规整：{}", value);
        }
        return value.replaceAll("/+$", "").replaceAll("(?i)/api/v1$", "");
    }

    /** null=不修改；空串=落库空白（读取时回退 env 默认）；非空=校验后保存 */
    private String validateBaseUrl(String raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_URL_LENGTH) {
            throw new BizException(field + "长度不能超过 " + MAX_URL_LENGTH);
        }
        if (!value.matches("^https?://\\S+$")) {
            throw new BizException(field + "必须是以 http:// 或 https:// 开头的地址");
        }
        return value;
    }

    private String validateOptionalText(String raw, String field, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > max) {
            throw new BizException(field + "长度不能超过 " + max);
        }
        return value;
    }

    /** 空/未指定 = 保持原密钥（沿用供应商更新的既有约定） */
    private String validateApiKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_API_KEY_LENGTH) {
            throw new BizException("API Key 过长（最多 " + MAX_API_KEY_LENGTH + " 字符）");
        }
        return value;
    }

    private AsrProvider.Config prospectiveLocal(LocalValues values, Integer newTimeout) {
        AsrProvider.Config current = localSettings();
        int timeout = newTimeout == null ? current.timeoutSeconds() : newTimeout;
        if (values == null) {
            return new AsrProvider.Config(
                    current.baseUrl(),
                    current.apiKey(),
                    current.model(),
                    current.audioPath(),
                    List.of(),
                    timeout);
        }
        String baseUrl =
                effective(values.baseUrl(), current.baseUrl(), properties.getLocal().getBaseUrl());
        String model = effective(values.model(), current.model(), properties.getLocal().getModel());
        String apiKey = values.apiKey() == null ? current.apiKey() : values.apiKey();
        return new AsrProvider.Config(
                baseUrl, apiKey, model, current.audioPath(), List.of(), timeout);
    }

    private AsrProvider.Config prospectiveDashscope(DashScopeValues values, Integer newTimeout) {
        AsrProvider.Config current = dashscopeSettings();
        int timeout = newTimeout == null ? current.timeoutSeconds() : newTimeout;
        if (values == null) {
            return new AsrProvider.Config(
                    current.baseUrl(),
                    current.apiKey(),
                    current.model(),
                    "",
                    current.languageHints(),
                    timeout);
        }
        String baseUrl =
                effective(
                        values.baseUrl(),
                        current.baseUrl(),
                        properties.getDashscope().getBaseUrl());
        String model =
                effective(values.model(), current.model(), properties.getDashscope().getModel());
        String hints =
                effective(
                        values.languageHints(),
                        String.join(",", current.languageHints()),
                        properties.getDashscope().getLanguageHints());
        String apiKey = values.apiKey() == null ? current.apiKey() : values.apiKey();
        return new AsrProvider.Config(
                baseUrl, apiKey, model, "", parseLanguageHints(hints), timeout);
    }

    /** null=保持当前生效值；空串=回退 env 默认；非空=新值（仅非敏感项允许 env 默认） */
    private static String effective(String validated, String currentValue, String envDefault) {
        if (validated == null) {
            return currentValue == null ? "" : currentValue;
        }
        return validated.isBlank() ? firstText(envDefault, "") : validated;
    }

    // ------------------------------------------------------------------ 工具

    private AsrProvider providerOf(AsrProviderType type) {
        for (AsrProvider provider : providers) {
            if (provider.type() == type) {
                return provider;
            }
        }
        throw new BizException(
                "语音转写 provider 未实现: " + type.value(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private List<String> dashscopeModels(String activeModel) {
        List<String> configured =
                properties.getDashscope().getModels() == null
                        ? List.of()
                        : properties.getDashscope().getModels();
        List<String> models = new ArrayList<>();
        for (String model : configured) {
            if (StringUtils.hasText(model) && !models.contains(model.trim())) {
                models.add(model.trim());
            }
        }
        // 允许手填模型名：把当前生效值并进候选，前端下拉可直接展示
        if (StringUtils.hasText(activeModel) && !models.contains(activeModel)) {
            models.add(activeModel);
        }
        return List.copyOf(models);
    }

    /** 逗号分隔语言提示 → 去空白/小写/去重；第一项作为结果 language */
    static List<String> parseLanguageHints(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!hints.contains(value)) {
                hints.add(value);
            }
        }
        return List.copyOf(hints);
    }

    private Optional<String> read(String key) {
        try {
            return settings.findById(key)
                    .map(AppSetting::getSettingValue)
                    .filter(StringUtils::hasText);
        } catch (Exception e) {
            log.warn("读取转写配置 {} 失败，按未配置处理：{}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void save(String key, String value) {
        try {
            settings.save(AppSetting.of(key, value));
        } catch (Exception e) {
            throw new BizException(
                    "语音转写设置保存失败：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String firstText(String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                if (StringUtils.hasText(candidate)) {
                    return candidate.trim();
                }
            }
        }
        return "";
    }
}
