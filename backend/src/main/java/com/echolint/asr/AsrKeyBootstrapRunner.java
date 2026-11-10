package com.echolint.asr;

import com.echolint.config.SecretCipher;
import com.echolint.entity.AppSetting;
import com.echolint.repository.AppSettingRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ASR API Key 一次性 bootstrap（docs/asr-and-settings-refactor.md §6.2）。
 *
 * <p>运行时密钥<b>只读 {@code app_settings}</b>（AES-GCM 加密，界面只回掩码）；环境变量 {@code WHISPER_API_KEY} / {@code
 * DASHSCOPE_API_KEY} 仅作为首次启动的引导值：DB 缺该 provider 的密钥时加密落库一次，之后不再读取环境变量。这样重启/换环境不会出现「Key 来自哪里」的双来源漂移。
 *
 * <p>本地 whisper.cpp 不校验密钥，若环境变量也未配置则使用默认值 {@code local} 落库，保证零配置可用。 本类失败只 WARN，不影响启动（与 {@link
 * com.echolint.service.ProviderRegistry} 的 seed 行为一致）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AsrKeyBootstrapRunner implements ApplicationRunner {

    /** 本地 provider 的默认密钥（whisper.cpp 不校验，但本服务要求非空） */
    static final String DEFAULT_LOCAL_API_KEY = "local";

    private final AppSettingRepository settings;
    private final AsrProperties properties;
    private final SecretCipher cipher;

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /**
     * 把 DB 中尚不存在的 ASR 密钥加密落库（幂等：已有非空值一律不覆盖）。
     *
     * @return 本次新写入的密钥数量（测试用）
     */
    public int bootstrap() {
        int seeded = 0;
        // 本地 provider：env 未设置时用默认值 local 落库，保证默认 provider 零配置可用
        seeded +=
                bootstrapKey(
                        AsrSettingsService.KEY_LOCAL_API_KEY,
                        AsrProviderType.LOCAL,
                        firstText(properties.getLocal().getApiKey(), DEFAULT_LOCAL_API_KEY),
                        StringUtils.hasText(properties.getLocal().getApiKey())
                                ? "环境变量 " + AsrProviderType.LOCAL.apiKeyEnv()
                                : "内置默认值");
        // 百炼：env 为空表示管理员还没配置，不落库（设置页录入后自然就绪）
        seeded +=
                bootstrapKey(
                        AsrSettingsService.KEY_DASHSCOPE_API_KEY,
                        AsrProviderType.DASHSCOPE,
                        properties.getDashscope().getApiKey(),
                        "环境变量 " + AsrProviderType.DASHSCOPE.apiKeyEnv());
        return seeded;
    }

    private int bootstrapKey(
            String settingKey, AsrProviderType type, String candidate, String source) {
        if (!StringUtils.hasText(candidate)) {
            return 0;
        }
        try {
            Optional<AppSetting> existing = settings.findById(settingKey);
            if (existing.map(AppSetting::getSettingValue)
                    .filter(StringUtils::hasText)
                    .isPresent()) {
                // 已有数据库密钥：绝不覆盖（换环境/升级都不会冲掉设置页录入的 Key）
                log.debug("{} 的 API Key 已在数据库中，跳过 bootstrap", type.label());
                return 0;
            }
            settings.save(AppSetting.of(settingKey, cipher.encrypt(candidate.trim())));
            log.info("{} 的 API Key 已从{}读取并加密落库（仅首次启动执行，之后以数据库为准）", type.label(), source);
            return 1;
        } catch (Exception e) {
            log.warn("{} 的 API Key bootstrap 失败（不影响启动，可在设置页手动录入）：{}", type.label(), e.getMessage());
            return 0;
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
