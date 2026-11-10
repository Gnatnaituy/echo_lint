package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * RAG embedding 供应商/模型解析器（设置页可切换，DB 优先、env 兜底）。
 *
 * <p>解析顺序：{@code app_settings} 的 {@value #KEY_PROVIDER} / {@value #KEY_MODEL} （设置页保存，DB 优先）→ {@link
 * RagProperties} 的 yml/环境变量默认值。维度、路径、超时等不随 模型热改，仍由 {@link RagProperties} 决定（换模型后需要重建索引）。
 *
 * <p>本组件同时被 {@link RagSettingsService}（视图/保存校验）与 {@link RagEmbeddingClient}
 * （真实调用/连通性测试/成本留痕）使用，避免两者互相依赖形成环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEmbeddingSettingsResolver {

    static final String KEY_PROVIDER = "rag.embedding.provider";
    static final String KEY_MODEL = "rag.embedding.model";

    /** 当前 provider/model 来自数据库（设置页保存过） */
    static final String SOURCE_DATABASE = "DATABASE";

    /** 当前 provider/model 来自 yml/环境变量默认值（未在设置页保存） */
    static final String SOURCE_ENV = "ENV";

    /** embedding 模型名最大长度（前端手填；超长直接 400，不落库） */
    static final int MAX_MODEL_LENGTH = 120;

    private final AppSettingRepository settings;
    private final ProviderRegistry providers;
    private final RagProperties properties;

    /**
     * 当前生效的 embedding 供应商/模型。
     *
     * @param providerId 供应商 id（小写，来自 DB 或 env 默认）
     * @param providerLabel 供应商展示名（供应商不存在时回退 id）
     * @param model 模型名（来自 DB 或 env 默认）
     * @param apiKeyConfigured 供应商存在且已配置 API Key（密钥只回布尔值）
     * @param source DATABASE 表示 provider/model 至少一项来自数据库，ENV 表示均来自默认值
     */
    public record Resolved(
            String providerId,
            String providerLabel,
            String model,
            boolean apiKeyConfigured,
            String source) {}

    /** 解析当前生效配置（不发起网络请求） */
    public Resolved resolve() {
        Optional<String> dbProvider = read(KEY_PROVIDER);
        Optional<String> dbModel = read(KEY_MODEL);

        String providerId =
                dbProvider
                        .map(RagEmbeddingSettingsResolver::normalizeProviderId)
                        .filter(StringUtils::hasText)
                        .orElseGet(properties::effectiveEmbeddingProviderId);
        String model =
                dbModel.map(String::trim)
                        .filter(StringUtils::hasText)
                        .orElseGet(properties::effectiveEmbeddingModel);

        ResolvedProvider provider = providers.find(providerId).orElse(null);
        return new Resolved(
                providerId,
                provider == null ? providerId : provider.displayLabel(),
                model,
                provider != null && provider.apiKeyConfigured(),
                dbProvider.isPresent() || dbModel.isPresent() ? SOURCE_DATABASE : SOURCE_ENV);
    }

    /** UI 候选模型（yml/environment 配置的候选；当前模型自动并入，允许前端手填其它模型）。 */
    public List<String> candidateModels(String currentModel) {
        List<String> configured =
                properties.getEmbedding() == null || properties.getEmbedding().getModels() == null
                        ? List.of()
                        : properties.getEmbedding().getModels();
        List<String> models = new ArrayList<>();
        for (String model : configured) {
            if (StringUtils.hasText(model) && !models.contains(model.trim())) {
                models.add(model.trim());
            }
        }
        if (StringUtils.hasText(currentModel) && !models.contains(currentModel.trim())) {
            models.add(currentModel.trim());
        }
        return List.copyOf(models);
    }

    /**
     * 保存前校验供应商：必须存在、enabled、已配置 API Key，否则抛可读 400。
     *
     * @return 校验通过的供应商（含展示名/密钥可用性）
     */
    public ResolvedProvider requireUsableProvider(String rawProviderId) {
        String providerId = normalizeProviderId(rawProviderId);
        if (!StringUtils.hasText(providerId)) {
            throw new BizException("embedding 供应商标识不能为空");
        }
        ResolvedProvider provider =
                providers
                        .find(providerId)
                        .orElseThrow(
                                () ->
                                        new BizException(
                                                "未知的 embedding 供应商: "
                                                        + (rawProviderId == null
                                                                ? ""
                                                                : rawProviderId.trim())));
        if (!provider.enabled()) {
            throw new BizException("embedding 供应商已停用: " + provider.displayLabel());
        }
        if (!provider.apiKeyConfigured()) {
            throw new BizException("embedding 供应商未配置 API Key: " + provider.displayLabel());
        }
        return provider;
    }

    /** 供应商标识统一为小写（与 ProviderRegistry 口径一致） */
    public static String normalizeProviderId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<String> read(String key) {
        try {
            return settings.findById(key)
                    .map(AppSetting::getSettingValue)
                    .filter(StringUtils::hasText);
        } catch (Exception e) {
            log.warn("读取 embedding 配置 {} 失败，按未配置处理（回退默认值）：{}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
