package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echolint.config.SecretCipher;
import com.echolint.entity.AppSetting;
import com.echolint.repository.AppSettingRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ASR 密钥一次性 bootstrap（§6.2）：DB 缺失时把环境变量/默认值加密落库一次， 已有数据库密钥绝不覆盖；此后运行时只读数据库。 */
@ExtendWith(MockitoExtension.class)
class AsrKeyBootstrapRunnerTest {

    @Mock private AppSettingRepository settings;

    private final Map<String, AppSetting> store = new HashMap<>();
    private final SecretCipher cipher = new SecretCipher("test-secret");
    private AsrProperties properties;
    private AsrKeyBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        store.clear();
        lenient()
                .when(settings.findById(anyString()))
                .thenAnswer(
                        inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        lenient()
                .when(settings.save(any(AppSetting.class)))
                .thenAnswer(
                        inv -> {
                            AppSetting setting = inv.getArgument(0);
                            store.put(setting.getSettingKey(), setting);
                            return setting;
                        });

        properties = new AsrProperties();
        properties.getLocal().setApiKey("");
        properties.getDashscope().setApiKey("");
        runner = new AsrKeyBootstrapRunner(settings, properties, cipher);
    }

    private String storedValue(String key) {
        AppSetting setting = store.get(key);
        return setting == null ? null : setting.getSettingValue();
    }

    @Test
    void importsEnvKeysOnceAndEncryptsThem() {
        properties.getLocal().setApiKey("local-env-key");
        properties.getDashscope().setApiKey("sk-env-1234");

        assertEquals(2, runner.bootstrap());

        String localCipher = storedValue(AsrSettingsService.KEY_LOCAL_API_KEY);
        assertEquals("local-env-key", cipher.decrypt(localCipher));
        String dashCipher = storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY);
        assertEquals("sk-env-1234", cipher.decrypt(dashCipher));
        // 数据库中只存密文
        assertFalse(localCipher.contains("local-env-key"));
        assertFalse(dashCipher.contains("sk-env-1234"));

        // 第二次启动：只写一次，已有密文保持不变
        assertEquals(0, runner.bootstrap());
        assertEquals(localCipher, storedValue(AsrSettingsService.KEY_LOCAL_API_KEY));
        assertEquals(dashCipher, storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY));
    }

    @Test
    void existingDatabaseKeyIsNeverOverwritten() {
        store.put(
                AsrSettingsService.KEY_LOCAL_API_KEY,
                AppSetting.of(AsrSettingsService.KEY_LOCAL_API_KEY, cipher.encrypt("db-key")));
        properties.getLocal().setApiKey("env-key");

        assertEquals(0, runner.bootstrap());

        assertEquals("db-key", cipher.decrypt(storedValue(AsrSettingsService.KEY_LOCAL_API_KEY)));
    }

    @Test
    void blankStoredValueIsTreatedAsMissing() {
        store.put(
                AsrSettingsService.KEY_LOCAL_API_KEY,
                AppSetting.of(AsrSettingsService.KEY_LOCAL_API_KEY, ""));
        properties.getLocal().setApiKey("env-key");

        assertEquals(1, runner.bootstrap());
        assertEquals("env-key", cipher.decrypt(storedValue(AsrSettingsService.KEY_LOCAL_API_KEY)));
    }

    @Test
    void localDefaultKeyIsSeededWhenEnvIsUnset() {
        // 本地 whisper.cpp 不校验密钥：env 未配置时用默认值 local 落库，保证默认 provider 零配置可用
        assertEquals(1, runner.bootstrap());

        assertEquals("local", cipher.decrypt(storedValue(AsrSettingsService.KEY_LOCAL_API_KEY)));
        assertNull(storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY), "百炼无 Key 时不落库");
    }

    @Test
    void bootstrapFailureDoesNotBreakStartup() {
        AppSettingRepository failing = mock(AppSettingRepository.class);
        when(failing.findById(anyString())).thenThrow(new RuntimeException("db down"));
        AsrKeyBootstrapRunner failingRunner =
                new AsrKeyBootstrapRunner(failing, properties, cipher);

        assertEquals(0, failingRunner.bootstrap());
        verify(failing, never()).save(any(AppSetting.class));
        assertTrue(store.isEmpty());
    }
}
