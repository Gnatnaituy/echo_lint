package com.echolint.service;

import com.echolint.config.ScreenModelProperties;
import com.echolint.config.SecretCipher;
import com.echolint.entity.AiProvider;
import com.echolint.exception.BizException;
import com.echolint.repository.AiProviderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AI 供应商注册表（Batch 3a）：数据库为唯一事实来源，yml 作为兜底。
 *
 * <p>启动时（{@link ApplicationRunner}）把 yml {@code app.screen-models.providers} 中
 * **尚未落库**的供应商加密导入（builtIn=true）；已存在的行（内置或自定义）一律不覆盖、不删除，
 * 这样后台改过的密钥/价格不会因为重启被 yml 冲掉。</p>
 *
 * <p>解析顺序：DB 行优先；yml 中仍未落库的供应商追加在后（防止导入失败导致整条流水线不可用）。
 * 解析时解密 API Key，供 {@link ScreenModelService} 按调用构造 WebClient。解密失败（主密钥变更）
 * 只记 WARN 并按「未配置密钥」处理，不影响服务启动。</p>
 */
@Slf4j
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProviderRegistry implements ApplicationRunner {

    private static final int MAX_LABEL_LENGTH = 64;
    private static final int MAX_URL_LENGTH = 255;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_MODELS = 50;
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999.999999");
    private static final String DEFAULT_CHAT_PATH = "/v1/chat/completions";

    private final AiProviderRepository repository;
    private final ScreenModelProperties props;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 启动导入

    @Override
    public void run(ApplicationArguments args) {
        seedFromConfig();
    }

    /**
     * 把 yml 中尚未落库的供应商加密导入。返回新增数量；单条失败不影响其它供应商。
     */
    public int seedFromConfig() {
        int seeded = 0;
        for (ScreenModelProperties.Provider provider : props.getProviders()) {
            String key = normalizeKey(provider.getId());
            if (key.isEmpty()) {
                log.warn("忽略未配置 id 的模型供应商：{}", provider.getBaseUrl());
                continue;
            }
            try {
                if (repository.existsByProviderKeyIgnoreCase(key)) {
                    continue;
                }
                AiProvider entity = new AiProvider();
                entity.setProviderKey(key);
                entity.setLabel(truncate(provider.displayLabel(), MAX_LABEL_LENGTH));
                entity.setBaseUrl(truncate(trimTrailingSlash(provider.getBaseUrl()), MAX_URL_LENGTH));
                entity.setChatPath(truncate(provider.getChatPath(), MAX_URL_LENGTH));
                entity.setApiKeyCipher(StringUtils.hasText(provider.getApiKey())
                        ? cipher.encrypt(provider.getApiKey()) : null);
                entity.setApiKeyMasked(cipher.mask(provider.getApiKey()));
                entity.setDefaultModel(truncate(provider.getDefaultModel(), MAX_MODEL_LENGTH));
                entity.setModelsJson(writeModels(provider.getModels()));
                entity.setJsonMode(provider.isJsonMode());
                entity.setMaxTokens(provider.getMaxTokens());
                entity.setInputPricePer1k(normalizePrice(provider.getInputPricePer1k()));
                entity.setOutputPricePer1k(normalizePrice(provider.getOutputPricePer1k()));
                entity.setEnabled(true);
                entity.setBuiltIn(true);
                repository.save(entity);
                seeded++;
                log.info("已从配置导入内置 AI 供应商 {}（{}）", key,
                        entity.getApiKeyCipher() == null ? "未配置 API Key" : "API Key 已加密落库");
            } catch (Exception e) {
                log.warn("导入内置 AI 供应商 {} 失败（不影响其它供应商）：{}", key, e.getMessage());
            }
        }
        if (seeded > 0) {
            log.info("AI 供应商初始化完成：新增 {} 个内置供应商；库中已有供应商保持不变", seeded);
        }
        return seeded;
    }

    // ------------------------------------------------------------------ 解析

    /** 全部供应商：数据库行在前，yml 兜底项追加在后（按 providerKey 去重） */
    public List<ResolvedProvider> all() {
        Map<String, ResolvedProvider> resolved = new LinkedHashMap<>();
        List<AiProvider> rows;
        try {
            rows = repository.findAllByOrderByBuiltInDescProviderKeyAsc();
        } catch (Exception e) {
            log.warn("读取 AI 供应商失败，回退到配置文件供应商：{}", e.getMessage());
            rows = List.of();
        }
        for (AiProvider row : rows) {
            ResolvedProvider provider = fromEntity(row);
            if (provider != null) {
                resolved.putIfAbsent(provider.providerKey(), provider);
            }
        }
        for (ScreenModelProperties.Provider provider : props.getProviders()) {
            String key = normalizeKey(provider.getId());
            if (!key.isEmpty() && !resolved.containsKey(key)) {
                resolved.put(key, fromConfig(provider));
            }
        }
        return List.copyOf(resolved.values());
    }

    /** 按 key 查找（忽略大小写）：DB 优先，yml 兜底 */
    public Optional<ResolvedProvider> find(String providerKey) {
        String key = normalizeKey(providerKey);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        Optional<AiProvider> row = repository.findByProviderKeyIgnoreCase(key);
        if (row.isPresent()) {
            return Optional.ofNullable(fromEntity(row.get()));
        }
        return props.find(key).map(this::fromConfig);
    }

    /** 界面展示用快照（只含掩码，绝不包含密文/明文） */
    public List<ProviderSnapshot> snapshots() {
        return all().stream().map(ProviderRegistry::toSnapshot).toList();
    }

    /** 由实体生成快照（CRUD 返回用，避免再查一次库） */
    public ProviderSnapshot snapshotOf(AiProvider entity) {
        return toSnapshot(fromEntity(entity));
    }

    public static ProviderSnapshot toSnapshot(ResolvedProvider provider) {
        return new ProviderSnapshot(
                provider.providerKey(),
                provider.displayLabel(),
                provider.baseUrl(),
                provider.chatPath(),
                provider.defaultModel(),
                provider.models(),
                provider.jsonMode(),
                provider.maxTokens(),
                provider.inputPricePer1k(),
                provider.outputPricePer1k(),
                provider.enabled(),
                provider.builtIn(),
                provider.apiKeyMasked(),
                provider.apiKeyConfigured());
    }

    private ResolvedProvider fromEntity(AiProvider row) {
        String key = normalizeKey(row.getProviderKey());
        if (key.isEmpty()) {
            return null;
        }
        String apiKey = null;
        if (StringUtils.hasText(row.getApiKeyCipher())) {
            try {
                apiKey = cipher.decrypt(row.getApiKeyCipher());
            } catch (Exception e) {
                log.warn("AI 供应商 {} 的 API Key 解密失败（APP_SECRET_KEY 是否已变更？），按未配置处理", key);
            }
        }
        // yml 里的 extra-body / api-key-env 不属于数据库字段：内置供应商按同 key 配置合并，保持既有行为
        ScreenModelProperties.Provider config = props.find(key).orElse(null);
        return new ResolvedProvider(
                key,
                StringUtils.hasText(row.getLabel()) ? row.getLabel() : key,
                trimTrailingSlash(row.getBaseUrl()),
                StringUtils.hasText(row.getChatPath()) ? row.getChatPath() : DEFAULT_CHAT_PATH,
                apiKey,
                row.getDefaultModel(),
                readModels(row.getModelsJson()),
                row.isJsonMode(),
                row.getMaxTokens(),
                normalizePrice(row.getInputPricePer1k()),
                normalizePrice(row.getOutputPricePer1k()),
                row.isEnabled(),
                row.isBuiltIn(),
                config == null || config.getExtraBody() == null ? Map.of() : Map.copyOf(config.getExtraBody()),
                config == null ? null : config.getApiKeyEnv(),
                StringUtils.hasText(row.getApiKeyMasked()) ? row.getApiKeyMasked() : cipher.mask(apiKey));
    }

    private ResolvedProvider fromConfig(ScreenModelProperties.Provider provider) {
        String key = normalizeKey(provider.getId());
        return new ResolvedProvider(
                key,
                provider.displayLabel(),
                trimTrailingSlash(provider.getBaseUrl()),
                StringUtils.hasText(provider.getChatPath()) ? provider.getChatPath() : DEFAULT_CHAT_PATH,
                StringUtils.hasText(provider.getApiKey()) ? provider.getApiKey() : null,
                provider.getDefaultModel(),
                List.copyOf(provider.getModels() == null ? List.of() : provider.getModels()),
                provider.isJsonMode(),
                provider.getMaxTokens(),
                normalizePrice(provider.getInputPricePer1k()),
                normalizePrice(provider.getOutputPricePer1k()),
                true,
                true,
                provider.getExtraBody() == null ? Map.of() : Map.copyOf(provider.getExtraBody()),
                provider.getApiKeyEnv(),
                cipher.mask(provider.getApiKey()));
    }

    // ------------------------------------------------------------------ 工具

    /** 供应商标识统一为小写（DB/配置/界面三处口径一致） */
    static String normalizeKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    static String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 价格统一为 0 或非负、最多 6 位小数；null 视为 0 */
    static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return price.min(MAX_PRICE).setScale(6, RoundingMode.HALF_UP);
    }

    static BigDecimal validatePrice(BigDecimal price, String field) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        if (price.signum() < 0) {
            throw new BizException(field + "不能为负数");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new BizException(field + "不能超过 " + MAX_PRICE.toPlainString());
        }
        return price.setScale(6, RoundingMode.HALF_UP);
    }

    String writeModels(List<String> models) {
        List<String> normalized = normalizeModels(models);
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            log.warn("AI 供应商候选模型序列化失败：{}", e.getMessage());
            return "[]";
        }
    }

    static List<String> normalizeModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String model : models) {
            if (!StringUtils.hasText(model)) {
                continue;
            }
            String value = truncate(model.trim(), MAX_MODEL_LENGTH);
            if (!value.isEmpty()) {
                unique.add(value);
            }
            if (unique.size() >= MAX_MODELS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    List<String> readModels(String modelsJson) {
        if (!StringUtils.hasText(modelsJson)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(modelsJson, new TypeReference<List<String>>() {
            });
            return normalizeModels(parsed);
        } catch (Exception e) {
            log.warn("AI 供应商候选模型 JSON 解析失败，按空列表处理：{}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    // ------------------------------------------------------------------ DTO

    /**
     * 供应商解析结果：包含解密后的 API Key（只在进程内使用，绝不写日志/响应）。
     */
    public record ResolvedProvider(
            String providerKey,
            String label,
            String baseUrl,
            String chatPath,
            String apiKey,
            String defaultModel,
            List<String> models,
            boolean jsonMode,
            Integer maxTokens,
            BigDecimal inputPricePer1k,
            BigDecimal outputPricePer1k,
            boolean enabled,
            boolean builtIn,
            Map<String, Object> extraBody,
            String apiKeyEnv,
            String apiKeyMasked) {

        public boolean apiKeyConfigured() {
            return StringUtils.hasText(apiKey);
        }

        public String displayLabel() {
            return StringUtils.hasText(label) ? label : providerKey;
        }

        /** 未指定模型时回退到 default-model，再回退到候选列表首项 */
        public String resolvedModel(String requested) {
            if (StringUtils.hasText(requested)) {
                return requested.trim();
            }
            if (StringUtils.hasText(defaultModel)) {
                return defaultModel.trim();
            }
            return models.isEmpty() ? "" : models.get(0);
        }

        /** 密钥缺失提示（内置供应商提示对应环境变量，自定义提示去系统设置页填写） */
        public String apiKeyHint() {
            return StringUtils.hasText(apiKeyEnv)
                    ? "请设置环境变量 " + apiKeyEnv
                    : "请在「系统设置」中为供应商 " + providerKey + " 配置 API Key";
        }
    }

    /**
     * 界面展示用供应商快照（与前端契约字段一致；不含密文/明文密钥）。
     */
    public record ProviderSnapshot(
            String key,
            String label,
            String baseUrl,
            String chatPath,
            String defaultModel,
            List<String> models,
            boolean jsonMode,
            Integer maxTokens,
            BigDecimal inputPricePer1k,
            BigDecimal outputPricePer1k,
            boolean enabled,
            boolean builtIn,
            String apiKeyMasked,
            boolean apiKeyConfigured) {
    }
}
