package com.echolint.service;

import com.echolint.config.SecretCipher;
import com.echolint.dto.ProviderRequest;
import com.echolint.entity.AiProvider;
import com.echolint.entity.AppSetting;
import com.echolint.exception.BizException;
import com.echolint.repository.AiProviderRepository;
import com.echolint.repository.AppSettingRepository;
import com.echolint.service.ProviderRegistry.ProviderSnapshot;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 供应商 CRUD（Batch 3a）：新增 key 规范化/唯一、密钥加密与掩码、修改时密钥留空保持、
 * 删除守卫（内置 / 当前生效）、价格与模型校验。
 */
@ExtendWith(MockitoExtension.class)
class ProviderAdminServiceTest {

    @Mock
    private AiProviderRepository repository;
    @Mock
    private AppSettingRepository settingRepository;
    @Mock
    private ProviderRegistry registry;

    private final SecretCipher cipher = new SecretCipher("test-secret");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProviderAdminService service;

    @BeforeEach
    void setUp() {
        service = new ProviderAdminService(repository, settingRepository, registry, cipher, objectMapper);
        lenient().when(repository.save(any(AiProvider.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(registry.snapshotOf(any(AiProvider.class)))
                .thenAnswer(inv -> ProviderRegistryTestSupport.snapshot(inv.getArgument(0), cipher));
        lenient().when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private ProviderRequest request(String key, String apiKey) {
        return new ProviderRequest(key, " My Provider ", " https://api.example.com/ ",
                "/v1/chat/completions", " model-a ", List.of("model-a", "model-b", "model-a"),
                null, 2048, new BigDecimal("0.01"), new BigDecimal("0.02"), null, apiKey);
    }

    private AiProvider stored(String key, boolean builtIn, String apiKey) {
        AiProvider entity = new AiProvider();
        entity.setProviderKey(key);
        entity.setLabel("Old Label");
        entity.setBaseUrl("https://old.example.com");
        entity.setChatPath("/v1/chat/completions");
        entity.setApiKeyCipher(apiKey == null ? null : cipher.encrypt(apiKey));
        entity.setApiKeyMasked(cipher.mask(apiKey));
        entity.setDefaultModel("old-model");
        entity.setModelsJson("[]");
        entity.setJsonMode(true);
        entity.setEnabled(true);
        entity.setBuiltIn(builtIn);
        return entity;
    }

    @Test
    void createNormalizesSlugAndEncryptsApiKey() {
        when(registry.find("my-provider")).thenReturn(Optional.empty());

        ProviderSnapshot snapshot = service.create(request(" My-Provider ", " sk-custom-1234 "));

        ArgumentCaptor<AiProvider> captor = ArgumentCaptor.forClass(AiProvider.class);
        verify(repository).save(captor.capture());
        AiProvider saved = captor.getValue();
        assertEquals("my-provider", saved.getProviderKey(), "key 统一小写");
        assertFalse(saved.isBuiltIn(), "自定义供应商不是内置");
        assertEquals("My Provider", saved.getLabel());
        assertEquals("https://api.example.com", saved.getBaseUrl(), "去掉末尾斜杠");
        assertEquals("sk-custom-1234", cipher.decrypt(saved.getApiKeyCipher()));
        assertNotEquals("sk-custom-1234", saved.getApiKeyCipher());
        assertEquals("****1234", saved.getApiKeyMasked());
        assertEquals("[\"model-a\",\"model-b\"]", saved.getModelsJson(), "候选模型去重");
        assertTrue(saved.isJsonMode(), "jsonMode 缺省 true");
        assertTrue(saved.isEnabled(), "enabled 缺省 true");
        assertEquals(6, saved.getInputPricePer1k().scale());
        assertEquals("my-provider", snapshot.key());
    }

    @Test
    void createRejectsDuplicateKey() {
        ResolvedProvider existing = new ResolvedProvider("my-provider", "L", "https://x", "/v1/chat/completions",
                null, "m", List.of(), true, null, BigDecimal.ZERO, BigDecimal.ZERO, true, true,
                Map.of(), null, null);
        when(registry.find("my-provider")).thenReturn(Optional.of(existing));

        BizException error = assertThrows(BizException.class, () -> service.create(request("my-provider", "")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("已存在"));
        verify(repository, never()).save(any(AiProvider.class));
    }

    @Test
    void createRejectsInvalidSlug() {
        BizException error = assertThrows(BizException.class, () -> service.create(request("bad key!", "")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("供应商标识"));
    }

    @Test
    void createRejectsNegativePrice() {
        ProviderRequest invalid = new ProviderRequest("custom-a", "Label", "https://api.example.com",
                "/v1/chat/completions", "model-a", List.of(), true, null,
                new BigDecimal("-0.01"), BigDecimal.ZERO, true, "");
        when(registry.find("custom-a")).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.create(invalid));

        assertTrue(error.getMessage().contains("不能为负数"));
        verify(repository, never()).save(any(AiProvider.class));
    }

    @Test
    void createRejectsNonHttpBaseUrl() {
        ProviderRequest invalid = new ProviderRequest("custom-a", "Label", "ftp://api.example.com",
                "/v1/chat/completions", "model-a", List.of(), true, null,
                BigDecimal.ZERO, BigDecimal.ZERO, true, "");
        when(registry.find("custom-a")).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.create(invalid));

        assertTrue(error.getMessage().contains("base-url"));
    }

    @Test
    void updateKeepsExistingApiKeyWhenBlank() {
        AiProvider entity = stored("custom-a", false, "sk-old-9999");
        when(repository.findByProviderKeyIgnoreCase("custom-a")).thenReturn(Optional.of(entity));
        ProviderRequest update = new ProviderRequest(null, "New Label", "https://api.example.com",
                "/v1/chat/completions", "model-new", List.of("model-new"), true, 1024,
                new BigDecimal("0.03"), new BigDecimal("0.04"), false, "  ");

        service.update("custom-a", update);

        assertEquals("sk-old-9999", cipher.decrypt(entity.getApiKeyCipher()), "空 apiKey 表示保持原密钥");
        assertEquals("****9999", entity.getApiKeyMasked());
        assertEquals("New Label", entity.getLabel());
        assertEquals("model-new", entity.getDefaultModel());
        assertFalse(entity.isEnabled());
        assertEquals(0, new BigDecimal("0.03").compareTo(entity.getInputPricePer1k()));
    }

    @Test
    void updateRotatesApiKey() {
        AiProvider entity = stored("custom-a", false, "sk-old-9999");
        when(repository.findByProviderKeyIgnoreCase("custom-a")).thenReturn(Optional.of(entity));
        ProviderRequest update = new ProviderRequest(null, "L", "https://api.example.com",
                "/v1/chat/completions", "m", List.of(), true, null,
                BigDecimal.ZERO, BigDecimal.ZERO, true, "sk-new-5678");

        service.update("custom-a", update);

        assertEquals("sk-new-5678", cipher.decrypt(entity.getApiKeyCipher()));
        assertEquals("****5678", entity.getApiKeyMasked());
    }

    @Test
    void updateUnknownProviderIs404() {
        when(repository.findByProviderKeyIgnoreCase("ghost")).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class,
                () -> service.update("ghost", request(null, "")));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void deleteRejectsBuiltInProvider() {
        when(repository.findByProviderKeyIgnoreCase("openai"))
                .thenReturn(Optional.of(stored("openai", true, "sk-openai")));

        BizException error = assertThrows(BizException.class, () -> service.delete("openai"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("内置"));
        verify(repository, never()).delete(any(AiProvider.class));
    }

    @Test
    void deleteRejectsCurrentlyActiveProvider() {
        when(repository.findByProviderKeyIgnoreCase("custom-a"))
                .thenReturn(Optional.of(stored("custom-a", false, null)));
        when(settingRepository.findById(ScreenModelService.KEY_PROVIDER))
                .thenReturn(Optional.of(AppSetting.of(ScreenModelService.KEY_PROVIDER, "custom-a")));

        BizException error = assertThrows(BizException.class, () -> service.delete("custom-a"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("当前生效"));
        verify(repository, never()).delete(any(AiProvider.class));
    }

    @Test
    void deleteCustomInactiveProviderSucceeds() {
        AiProvider entity = stored("custom-a", false, null);
        when(repository.findByProviderKeyIgnoreCase("custom-a")).thenReturn(Optional.of(entity));

        service.delete("CUSTOM-A");

        verify(repository).delete(entity);
    }

    @Test
    void listDelegatesToRegistrySnapshots() {
        ProviderSnapshot snapshot = new ProviderSnapshot("custom-a", "L", "https://x",
                "/v1/chat/completions", "m", List.of("m"), true, null, BigDecimal.ZERO,
                BigDecimal.ZERO, true, false, "****1234", true);
        when(registry.snapshots()).thenReturn(List.of(snapshot));

        assertEquals(List.of(snapshot), service.list());
    }

    /** 测试辅助：把实体映射成快照（生产逻辑在 ProviderRegistry） */
    private static final class ProviderRegistryTestSupport {
        static ProviderSnapshot snapshot(AiProvider entity, SecretCipher cipher) {
            return new ProviderSnapshot(entity.getProviderKey(), entity.getLabel(), entity.getBaseUrl(),
                    entity.getChatPath(), entity.getDefaultModel(), List.of(), entity.isJsonMode(),
                    entity.getMaxTokens(), entity.getInputPricePer1k(), entity.getOutputPricePer1k(),
                    entity.isEnabled(), entity.isBuiltIn(), entity.getApiKeyMasked(),
                    entity.getApiKeyCipher() != null);
        }
    }
}
