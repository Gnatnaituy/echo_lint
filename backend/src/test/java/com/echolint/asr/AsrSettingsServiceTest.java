package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.echolint.config.SecretCipher;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** 语音转写设置：密钥只读数据库（§6.2）、视图字段契约、PUT 空 apiKey 保持原值、 未就绪拒绝切换、默认 provider、解密失败降级、超时钳制。 */
@ExtendWith(MockitoExtension.class)
class AsrSettingsServiceTest {

    private static final String DASHSCOPE_MODEL = "qwen-audio-3.1-asr-flash-filetrans";

    @Mock private AppSettingRepository settings;

    /** 内存版 app_settings，模拟持久化 */
    private final Map<String, AppSetting> store = new HashMap<>();

    private final SecretCipher cipher = new SecretCipher("test-secret");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AsrProperties properties;
    private AsrSettingsService service;

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
        properties.setTimeoutSeconds(600);
        properties.getLocal().setBaseUrl("http://127.0.0.1:9900");
        properties.getLocal().setApiKey("");
        properties.getLocal().setModel("whisper-1");
        properties.getDashscope().setBaseUrl("https://dashscope.aliyuncs.com");
        properties.getDashscope().setApiKey("");
        properties.getDashscope().setModel(DASHSCOPE_MODEL);
        properties.getDashscope().setLanguageHints("zh");
        service = newService();
    }

    private AsrSettingsService newService() {
        return new AsrSettingsService(
                settings,
                properties,
                cipher,
                List.of(
                        new LocalWhisperProvider(objectMapper),
                        new DashScopeAsrProvider(objectMapper)));
    }

    private void seed(String key, String value) {
        store.put(key, AppSetting.of(key, value));
    }

    /** 模拟「设置页录入 / 启动 bootstrap」后的数据库密文密钥 */
    private void seedEncryptedKey(String settingKey, String plaintext) {
        seed(settingKey, cipher.encrypt(plaintext));
    }

    private String storedValue(String key) {
        AppSetting setting = store.get(key);
        return setting == null ? null : setting.getSettingValue();
    }

    // ------------------------------------------------------------------ 默认值/回退

    @Test
    void viewUsesOnlyDatabaseKeysAndOmitsEnvFields() throws Exception {
        // 环境变量里的密钥不再参与运行时解析（仅启动 bootstrap 时使用）
        properties.getLocal().setApiKey("local");
        properties.getDashscope().setApiKey("sk-env-key-abcd");

        AsrSettingsService.View view = service.view();

        assertEquals("local", view.activeProvider(), "默认 provider 为 local");
        assertEquals(600, view.timeoutSeconds());

        AsrSettingsService.LocalView local = view.local();
        assertEquals("本地 whisper.cpp", local.label());
        assertFalse(local.ready(), "DB 无密钥即未就绪，env 密钥不再生效");
        assertEquals("http://127.0.0.1:9900", local.baseUrl());
        assertEquals("whisper-1", local.model());
        assertEquals("/v1/audio/transcriptions", local.audioPath());
        assertFalse(local.apiKeyConfigured());
        assertNull(local.apiKeyMasked());

        AsrSettingsService.DashScopeView dashscope = view.dashscope();
        assertEquals("阿里云百炼", dashscope.label());
        assertFalse(dashscope.ready());
        assertEquals("https://dashscope.aliyuncs.com", dashscope.baseUrl());
        assertEquals(DASHSCOPE_MODEL, dashscope.model());
        assertEquals(
                List.of("qwen-audio-3.1-asr-flash-filetrans", "qwen-audio-3.0-asr-flash-filetrans"),
                dashscope.models());
        assertEquals("zh", dashscope.languageHints());
        assertFalse(dashscope.apiKeyConfigured());
        assertNull(dashscope.apiKeyMasked());

        // §6.2：视图不再包含 apiKeySource / envHints
        String json = objectMapper.writeValueAsString(view);
        assertFalse(json.contains("apiKeySource"), json);
        assertFalse(json.contains("envHints"), json);
        assertTrue(json.contains("apiKeyConfigured"), json);
        assertTrue(json.contains("apiKeyMasked"), json);
    }

    @Test
    void databaseKeysMakeProvidersReadyAndAreMasked() {
        seedEncryptedKey(AsrSettingsService.KEY_LOCAL_API_KEY, "local");
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");

        AsrSettingsService.View view = service.view();

        assertTrue(view.local().ready());
        assertTrue(view.local().apiKeyConfigured());
        assertEquals("****ocal", view.local().apiKeyMasked(), "只回末 4 位掩码");
        assertTrue(view.dashscope().ready());
        assertTrue(view.dashscope().apiKeyConfigured());
        assertEquals("****abcd", view.dashscope().apiKeyMasked());
        // 仅保存百炼 Key 不隐式切云
        assertEquals("local", view.activeProvider());
        assertEquals(AsrProviderType.LOCAL, service.current().type());
    }

    @Test
    void storedProviderWinsWhenDashScopeReady() {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");
        seed(AsrSettingsService.KEY_PROVIDER, "dashscope");

        assertEquals(AsrProviderType.DASHSCOPE, service.current().type(), "DB 已保存的 provider 优先");
        assertEquals("dashscope", service.view().activeProvider());
    }

    @Test
    void savedDashScopeProviderFallsBackToLocalWhenKeyRemoved() {
        seed(AsrSettingsService.KEY_PROVIDER, "dashscope");
        // DB 无百炼密钥 → 未就绪，回退本地，避免整条流水线失败
        assertEquals("local", service.current().type().value());
        assertEquals("local", service.view().activeProvider());
    }

    @Test
    void invalidStoredProviderIsTreatedAsUnsetAndDefaultsToLocal() {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");
        seed(AsrSettingsService.KEY_PROVIDER, "whisper");

        assertEquals("local", service.view().activeProvider());
    }

    @Test
    void unreadyCurrentConfigExplainsWhatIsMissingWithoutEnvNames() {
        AsrSettingsService.Resolved resolved = service.current();

        assertEquals(AsrProviderType.LOCAL, resolved.type());
        assertFalse(resolved.ready());
        String message = resolved.unreadyMessage();
        assertTrue(message.contains("本地 whisper.cpp"), message);
        assertTrue(message.contains("未配置API Key"), message);
        assertTrue(message.contains("请在设置页录入 API Key"), message);
        assertFalse(message.contains("WHISPER_API_KEY"), "不再提示环境变量名: " + message);
        assertFalse(message.contains("环境变量"), message);
    }

    @Test
    void timeoutFallsBackToDefaultWhenStoredValueIsInvalid() {
        seed(AsrSettingsService.KEY_TIMEOUT_SECONDS, "not-a-number");

        assertEquals(600, service.view().timeoutSeconds());
    }

    // ------------------------------------------------------------------ 密钥/持久化

    @Test
    void savingApiKeyEncryptsItAndMasksTheView() {
        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                null,
                                new AsrSettingsService.DashScopeInput(
                                        null, null, null, "sk-db-key-1234")));

        String stored = storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY);
        assertNotEquals("sk-db-key-1234", stored, "DB 只存密文");
        assertEquals("sk-db-key-1234", cipher.decrypt(stored));
        assertFalse(view.dashscope().apiKeyMasked().contains("sk-db-key"));
        assertEquals("****1234", view.dashscope().apiKeyMasked());
        assertTrue(view.dashscope().apiKeyConfigured());
        // 仅保存 Key 不等于切换，当前 provider 保持 local 并显式落库
        assertEquals("local", view.activeProvider());
        assertEquals("local", storedValue(AsrSettingsService.KEY_PROVIDER));
    }

    @Test
    void emptyApiKeyKeepsStoredKey() {
        service.update(
                new AsrSettingsService.UpdateRequest(
                        null,
                        null,
                        null,
                        new AsrSettingsService.DashScopeInput(
                                null, null, null, "sk-keep-key-9999")));
        String storedBefore = storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY);

        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                null,
                                new AsrSettingsService.DashScopeInput(null, null, null, "")));

        assertEquals(
                storedBefore,
                storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY),
                "空 apiKey = 保持原值（沿用供应商更新约定）");
        assertEquals("****9999", view.dashscope().apiKeyMasked());
        assertEquals("sk-keep-key-9999", cipher.decrypt(storedBefore));

        // null 同样表示不修改
        service.update(
                new AsrSettingsService.UpdateRequest(
                        null,
                        null,
                        null,
                        new AsrSettingsService.DashScopeInput(null, null, null, null)));
        assertEquals(storedBefore, storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY));
    }

    @Test
    void decryptionFailureIsTreatedAsUnconfiguredEvenIfEnvKeyPresent() {
        // 环境变量有值也不再生效（§6.2 运行时只读 DB）
        properties.getDashscope().setApiKey("sk-env-key-abcd");
        seed(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "not-a-valid-cipher");

        AsrSettingsService.DashScopeView dashscope = service.view().dashscope();

        assertFalse(dashscope.ready());
        assertFalse(dashscope.apiKeyConfigured());
        assertNull(dashscope.apiKeyMasked());
    }

    @Test
    void viewNeverContainsPlaintextOrCiphertextKey() throws Exception {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-super-secret-9876");
        String ciphertext = storedValue(AsrSettingsService.KEY_DASHSCOPE_API_KEY);

        String json = objectMapper.writeValueAsString(service.view());

        assertFalse(json.contains("sk-super-secret-9876"), "视图绝不能回明文密钥");
        assertFalse(json.contains(ciphertext), "视图绝不能回密文");
        assertTrue(json.contains("****9876"));
    }

    @Test
    void savedSettingsTakePriorityOverEnv() {
        seed(
                AsrSettingsService.KEY_DASHSCOPE_BASE_URL,
                "https://workspace.cn-beijing.maas.aliyuncs.com");
        seed(AsrSettingsService.KEY_DASHSCOPE_MODEL, "qwen-audio-3.0-asr-flash-filetrans");
        seed(AsrSettingsService.KEY_DASHSCOPE_LANGUAGE_HINTS, "en,zh");
        seed(AsrSettingsService.KEY_TIMEOUT_SECONDS, "900");

        AsrSettingsService.View view = service.view();

        assertEquals("https://workspace.cn-beijing.maas.aliyuncs.com", view.dashscope().baseUrl());
        assertEquals("qwen-audio-3.0-asr-flash-filetrans", view.dashscope().model());
        assertEquals("en,zh", view.dashscope().languageHints());
        assertEquals(900, view.timeoutSeconds());
        assertTrue(view.dashscope().models().contains("qwen-audio-3.0-asr-flash-filetrans"));
    }

    @Test
    void updatePersistsConfigAndClampsTimeout() {
        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                5L,
                                new AsrSettingsService.LocalInput(
                                        "http://10.0.0.2:9900", "whisper-large", null),
                                null));

        assertEquals("http://10.0.0.2:9900", storedValue(AsrSettingsService.KEY_LOCAL_BASE_URL));
        assertEquals("whisper-large", storedValue(AsrSettingsService.KEY_LOCAL_MODEL));
        assertEquals("30", storedValue(AsrSettingsService.KEY_TIMEOUT_SECONDS), "超时下限 30s");
        assertEquals(30, view.timeoutSeconds());
        assertNull(storedValue(AsrSettingsService.KEY_LOCAL_API_KEY), "空 apiKey 不落库");

        AsrSettingsService.View upper =
                service.update(new AsrSettingsService.UpdateRequest(null, 9999L, null, null));
        assertEquals(1800, upper.timeoutSeconds(), "超时上限 1800s");
    }

    @Test
    void dashscopeBaseUrlIsNormalizedToHostRoot() {
        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                null,
                                new AsrSettingsService.DashScopeInput(
                                        "https://llm-abc.cn-beijing.maas.aliyuncs.com/api/v1/",
                                        null,
                                        null,
                                        null)));

        assertEquals("https://llm-abc.cn-beijing.maas.aliyuncs.com", view.dashscope().baseUrl());
        assertEquals(
                "https://llm-abc.cn-beijing.maas.aliyuncs.com",
                storedValue(AsrSettingsService.KEY_DASHSCOPE_BASE_URL));

        // 历史落库的完整接口地址也会在读取时归一化（避免 /api/v1/api/v1 拼接）
        seed(
                AsrSettingsService.KEY_DASHSCOPE_BASE_URL,
                "https://legacy.example.com/api/v1/services/audio/asr/transcription");
        assertEquals("https://legacy.example.com", service.view().dashscope().baseUrl());
    }

    @Test
    void blankBaseUrlResetsToEnvDefault() {
        seed(AsrSettingsService.KEY_LOCAL_BASE_URL, "http://db-host:9900");

        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                new AsrSettingsService.LocalInput("", null, null),
                                null));

        assertEquals("", storedValue(AsrSettingsService.KEY_LOCAL_BASE_URL), "落库空白");
        assertEquals("http://127.0.0.1:9900", view.local().baseUrl(), "读取时回退 env 默认值");
    }

    @Test
    void updateWithNullBodyKeepsEffectiveProvider() {
        AsrSettingsService.View view = service.update(null);

        assertEquals("local", view.activeProvider());
        assertEquals("local", storedValue(AsrSettingsService.KEY_PROVIDER));
        assertEquals(1, store.size(), "空 PUT 只把当前生效 provider 显式落库");
    }

    @Test
    void languageHintsAreNormalizedAndFirstIsUsed() {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");

        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                null,
                                new AsrSettingsService.DashScopeInput(
                                        null, null, " En , ZH,en ", null)));

        assertEquals("en,zh", view.dashscope().languageHints());
        assertEquals("en,zh", storedValue(AsrSettingsService.KEY_DASHSCOPE_LANGUAGE_HINTS));
        // 语言提示解析结果（第一项 en）由 provider 映射时使用
        assertEquals(List.of("en", "zh"), AsrSettingsService.parseLanguageHints(" En , ZH,en "));
    }

    // ------------------------------------------------------------------ 切换校验

    @Test
    void switchingToUnreadyDashScopeIsRejectedWithoutSaving() {
        BizException error =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        new AsrSettingsService.UpdateRequest(
                                                "dashscope", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("无法切换到阿里云百炼"), error.getMessage());
        assertTrue(error.getMessage().contains("请在设置页录入 API Key"), error.getMessage());
        assertFalse(error.getMessage().contains("DASHSCOPE_API_KEY"), error.getMessage());
        assertFalse(store.containsKey(AsrSettingsService.KEY_PROVIDER), "未就绪时不得切换 provider");
    }

    @Test
    void switchingToUnreadyLocalIsRejectedWithSettingsHint() {
        BizException error =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        new AsrSettingsService.UpdateRequest(
                                                "local", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("本地 whisper.cpp"), error.getMessage());
        assertTrue(error.getMessage().contains("请在设置页录入 API Key"), error.getMessage());
        assertFalse(error.getMessage().contains("WHISPER_API_KEY"), error.getMessage());
        assertFalse(store.containsKey(AsrSettingsService.KEY_PROVIDER));
    }

    @Test
    void explicitSwitchToDashScopeAfterDbKeySavedSucceeds() {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");

        AsrSettingsService.View view =
                service.update(new AsrSettingsService.UpdateRequest("dashscope", null, null, null));

        assertEquals("dashscope", storedValue(AsrSettingsService.KEY_PROVIDER));
        assertEquals("dashscope", view.activeProvider());
        assertTrue(view.dashscope().ready());
    }

    @Test
    void switchAndKeyInputInSameRequestSucceeds() {
        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                "dashscope",
                                null,
                                null,
                                new AsrSettingsService.DashScopeInput(
                                        null, null, null, "sk-abc-1234")));

        assertEquals("dashscope", storedValue(AsrSettingsService.KEY_PROVIDER));
        assertEquals("dashscope", view.activeProvider());
        assertTrue(view.dashscope().ready());
    }

    @Test
    void unknownActiveProviderIsRejected() {
        BizException error =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        new AsrSettingsService.UpdateRequest(
                                                "openai", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("未知的语音转写 provider"), error.getMessage());
        assertTrue(store.isEmpty());
    }

    @Test
    void savingConfigWithoutActiveProviderDoesNotSwitchProvider() {
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");
        seedEncryptedKey(AsrSettingsService.KEY_LOCAL_API_KEY, "local");
        seed(AsrSettingsService.KEY_PROVIDER, "local");

        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                new AsrSettingsService.LocalInput(
                                        "http://127.0.0.1:9911", null, null),
                                null));

        assertEquals("local", storedValue(AsrSettingsService.KEY_PROVIDER));
        assertEquals("local", view.activeProvider());
        assertEquals("http://127.0.0.1:9911", view.local().baseUrl());
    }

    @Test
    void updateWithoutActiveProviderPersistsEffectiveProvider() {
        seedEncryptedKey(AsrSettingsService.KEY_LOCAL_API_KEY, "local");
        // 首次 PUT 未带 activeProvider：把当前生效的 local 显式落库
        service.update(
                new AsrSettingsService.UpdateRequest(
                        null,
                        null,
                        new AsrSettingsService.LocalInput(null, "whisper-1", null),
                        null));
        assertEquals("local", storedValue(AsrSettingsService.KEY_PROVIDER));

        // 之后即使百炼 Key 已落库，也不应因就绪状态变化自动漂移到 dashscope
        seedEncryptedKey(AsrSettingsService.KEY_DASHSCOPE_API_KEY, "sk-db-key-abcd");
        AsrSettingsService.View view =
                service.update(
                        new AsrSettingsService.UpdateRequest(
                                null,
                                null,
                                new AsrSettingsService.LocalInput(null, null, null),
                                null));

        assertEquals("local", storedValue(AsrSettingsService.KEY_PROVIDER));
        assertEquals("local", view.activeProvider());
    }

    @Test
    void invalidBaseUrlIsRejectedBeforePersisting() {
        BizException error =
                assertThrows(
                        BizException.class,
                        () ->
                                service.update(
                                        new AsrSettingsService.UpdateRequest(
                                                null,
                                                null,
                                                new AsrSettingsService.LocalInput(
                                                        "127.0.0.1:9900", null, null),
                                                null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("http://"), error.getMessage());
        assertTrue(store.isEmpty());
    }

    // ------------------------------------------------------------------ 连通性测试

    @Test
    void testUnreadyProviderReturnsMessageWithoutNetwork() {
        AsrSettingsService.TestResult result = service.test(AsrProviderType.DASHSCOPE);

        assertFalse(result.ok());
        assertEquals("dashscope", result.provider());
        assertEquals(0, result.latencyMs());
        assertTrue(result.message().contains("API Key"), result.message());
        assertTrue(result.message().contains("请在设置页录入 API Key"), result.message());
        assertFalse(result.message().contains("DASHSCOPE_API_KEY"), result.message());
    }

    @Test
    void testDelegatesToReadyProviderAndFillsMetadata() {
        seedEncryptedKey(AsrSettingsService.KEY_LOCAL_API_KEY, "local");
        AsrProvider local = mock(AsrProvider.class);
        lenient().when(local.type()).thenReturn(AsrProviderType.LOCAL);
        lenient()
                .when(local.test(any(AsrProvider.Config.class)))
                .thenReturn(new AsrProvider.ConnectResult(true, "连通正常（本地 whisper.cpp 服务可达）"));
        AsrSettingsService mocked =
                new AsrSettingsService(settings, properties, cipher, List.of(local));

        AsrSettingsService.TestResult result = mocked.test(null);

        assertTrue(result.ok());
        assertEquals("local", result.provider());
        assertEquals("whisper-1", result.model());
        assertTrue(result.message().contains("连通正常"), result.message());
    }

    @Test
    void testConvertsProviderExceptionToFailureMessage() {
        seedEncryptedKey(AsrSettingsService.KEY_LOCAL_API_KEY, "local");
        AsrProvider local = mock(AsrProvider.class);
        lenient().when(local.type()).thenReturn(AsrProviderType.LOCAL);
        lenient()
                .when(local.test(any(AsrProvider.Config.class)))
                .thenThrow(new RuntimeException("连接被拒绝"));
        AsrSettingsService mocked =
                new AsrSettingsService(settings, properties, cipher, List.of(local));

        AsrSettingsService.TestResult result = mocked.test(AsrProviderType.LOCAL);

        assertFalse(result.ok());
        assertTrue(result.message().contains("调用异常"), result.message());
        assertTrue(result.message().contains("连接被拒绝"), result.message());
    }
}
