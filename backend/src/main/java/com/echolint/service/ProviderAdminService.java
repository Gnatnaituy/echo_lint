package com.echolint.service;

import com.echolint.config.SecretCipher;
import com.echolint.dto.ProviderRequest;
import com.echolint.entity.AiProvider;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AiProviderRepository;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry.ProviderSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * AI 供应商管理（Batch 3a，仅 ADMIN）：列表只回掩码，密钥加密落库。
 *
 * <p>约束：key 唯一且为小写 slug；删除仅限自定义且非当前生效供应商；
 * 修改时 apiKey 留空表示保持原密钥。所有写操作由 SettingsController 记审计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderAdminService {

    private static final int MAX_API_KEY_LENGTH = 4096;
    private static final int MAX_MODELS = 50;

    private final AiProviderRepository repository;
    private final AppSettingRepository settingRepository;
    private final ProviderRegistry registry;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    public List<ProviderSnapshot> list() {
        return registry.snapshots();
    }

    /** 新增自定义供应商（builtIn=false） */
    public ProviderSnapshot create(ProviderRequest request) {
        String key = normalizeCustomKey(request.key());
        if (registry.find(key).isPresent()) {
            throw new BizException("供应商标识已存在: " + key, HttpStatus.CONFLICT);
        }
        AiProvider entity = new AiProvider();
        entity.setProviderKey(key);
        entity.setBuiltIn(false);
        applyCommon(entity, request);
        applyApiKey(entity, request.apiKey(), true);
        AiProvider saved;
        try {
            saved = repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("供应商标识已存在: " + key, HttpStatus.CONFLICT);
        }
        log.info("新增 AI 供应商 {}（{}）", key, entity.isEnabled() ? "启用" : "停用");
        return registry.snapshotOf(saved == null ? entity : saved);
    }

    /** 修改供应商（key 不可变；内置供应商也可改密钥/价格/启用状态，但不能删除） */
    public ProviderSnapshot update(String key, ProviderRequest request) {
        AiProvider entity = requireEntity(key);
        applyCommon(entity, request);
        applyApiKey(entity, request.apiKey(), false);
        AiProvider saved = repository.save(entity);
        log.info("更新 AI 供应商 {}（{}）", entity.getProviderKey(), entity.isEnabled() ? "启用" : "停用");
        return registry.snapshotOf(saved == null ? entity : saved);
    }

    /** 删除自定义供应商：内置项与当前生效项禁止删除 */
    public void delete(String key) {
        AiProvider entity = requireEntity(key);
        if (entity.isBuiltIn()) {
            throw new BizException("内置供应商不可删除，可将其停用", HttpStatus.CONFLICT);
        }
        if (isActive(entity.getProviderKey())) {
            throw new BizException("当前生效的供应商不可删除，请先切换模型", HttpStatus.CONFLICT);
        }
        repository.delete(entity);
        log.info("删除 AI 供应商 {}", entity.getProviderKey());
    }

    // ------------------------------------------------------------------ 校验/映射

    private AiProvider requireEntity(String key) {
        String normalized = ProviderRegistry.normalizeKey(key);
        if (normalized.isEmpty()) {
            throw new BizException("供应商标识不能为空");
        }
        return repository.findByProviderKeyIgnoreCase(normalized)
                .orElseThrow(() -> new BizException("未知的模型供应商: " + key, HttpStatus.NOT_FOUND));
    }

    private void applyCommon(AiProvider entity, ProviderRequest request) {
        entity.setLabel(requireText(request.label(), "显示名称", 64));
        entity.setBaseUrl(validateBaseUrl(request.baseUrl()));
        String chatPath = StringUtils.hasText(request.chatPath())
                ? request.chatPath().trim() : "/v1/chat/completions";
        if (chatPath.length() > 255) {
            throw new BizException("chat-path 长度不能超过 255");
        }
        if (!chatPath.startsWith("/")) {
            throw new BizException("chat-path 必须以 / 开头");
        }
        entity.setChatPath(chatPath);
        entity.setDefaultModel(optionalText(request.defaultModel(), "default-model", 128));
        entity.setModelsJson(writeModels(request.models()));
        entity.setJsonMode(request.jsonMode() == null || request.jsonMode());
        entity.setMaxTokens(validateMaxTokens(request.maxTokens()));
        entity.setInputPricePer1k(ProviderRegistry.validatePrice(request.inputPricePer1k(), "输入价格"));
        entity.setOutputPricePer1k(ProviderRegistry.validatePrice(request.outputPricePer1k(), "输出价格"));
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private void applyApiKey(AiProvider entity, String apiKey, boolean creating) {
        if (apiKey == null || apiKey.isBlank()) {
            if (creating) {
                entity.setApiKeyCipher(null);
                entity.setApiKeyMasked(null);
            }
            // 修改时留空 = 保持原密钥不变
            return;
        }
        String value = apiKey.trim();
        if (value.length() > MAX_API_KEY_LENGTH) {
            throw new BizException("API Key 过长（最多 " + MAX_API_KEY_LENGTH + " 字符）");
        }
        entity.setApiKeyCipher(cipher.encrypt(value));
        entity.setApiKeyMasked(cipher.mask(value));
    }

    private String writeModels(List<String> models) {
        List<String> normalized = ProviderRegistry.normalizeModels(models);
        if (normalized.size() > MAX_MODELS) {
            normalized = normalized.subList(0, MAX_MODELS);
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BizException("候选模型序列化失败");
        }
    }

    private static Integer validateMaxTokens(Integer maxTokens) {
        if (maxTokens == null || maxTokens == 0) {
            return null;
        }
        if (maxTokens < 0) {
            throw new BizException("max-tokens 不能为负数");
        }
        if (maxTokens > 1_000_000) {
            throw new BizException("max-tokens 不能超过 1000000");
        }
        return maxTokens;
    }

    private static String validateBaseUrl(String raw) {
        String url = requireText(raw, "base-url", 255);
        if (!url.matches("^https?://\\S+$")) {
            throw new BizException("base-url 必须是以 http:// 或 https:// 开头的地址");
        }
        return ProviderRegistry.trimTrailingSlash(url);
    }

    private static String requireText(String value, String field, int max) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BizException(field + "长度不能超过 " + max);
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BizException(field + "长度不能超过 " + max);
        }
        return trimmed;
    }

    /** 小程序 slug：小写字母开头，只允许小写字母/数字/-/_，最长 64 */
    static String normalizeCustomKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException("供应商标识不能为空");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!key.matches("^[a-z][a-z0-9_-]{0,63}$")) {
            throw new BizException("供应商标识只能包含小写字母、数字、-、_，且必须以字母开头（最长 64 字符）");
        }
        return key;
    }

    private boolean isActive(String providerKey) {
        return settingRepository.findById(ScreenModelService.KEY_PROVIDER)
                .map(AppSetting::getSettingValue)
                .filter(StringUtils::hasText)
                .map(value -> value.trim().equalsIgnoreCase(providerKey))
                .orElse(false);
    }
}
