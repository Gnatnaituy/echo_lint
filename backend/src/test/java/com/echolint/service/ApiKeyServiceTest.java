package com.echolint.service;

import com.echolint.entity.ApiKey;
import com.echolint.exception.BizException;
import com.echolint.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 开放 API Key（Batch 4a）：格式/哈希、明文只返回一次、鉴权（停用/未知）、
 * last_used_at 更新、列表掩码、停用幂等。
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository repository;

    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository);
    }

    private ApiKey entity(long id, String plaintext, boolean enabled) {
        ApiKey key = new ApiKey();
        key.setId(id);
        key.setName("ci-runner");
        String prefix = plaintext.substring(0, 12);
        key.setKeyPrefix(ApiKeyService.mask(prefix, plaintext));
        key.setKeyHash(ApiKeyService.sha256(plaintext));
        key.setEnabled(enabled);
        key.setCreatedBy("admin");
        key.setCreatedAt(LocalDateTime.now().minusDays(1));
        return key;
    }

    // ------------------------------------------------------------------ 创建

    @Test
    void createReturnsPlaintextExactlyOnceAndStoresOnlyHash() {
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey key = inv.getArgument(0);
            key.setId(5L);
            key.setCreatedAt(LocalDateTime.now());
            return key;
        });

        ApiKeyService.CreatedApiKey created = service.create(" CI Runner ");

        assertTrue(created.key().matches("^elk_[A-Za-z0-9_-]{43}$"), created.key());
        assertEquals("CI Runner", created.name());
        assertEquals(47, created.key().length());
        assertEquals(created.key().substring(0, 12), created.prefix());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals("CI Runner", saved.getName());
        assertEquals(ApiKeyService.sha256(created.key()), saved.getKeyHash());
        assertNotEquals(created.key(), saved.getKeyHash());
        assertEquals(64, saved.getKeyHash().length());
        assertTrue(saved.getKeyHash().matches("[0-9a-f]{64}"));
        assertTrue(saved.getKeyPrefix().startsWith(created.prefix() + "..."));
        assertTrue(saved.getKeyPrefix().endsWith(created.key().substring(created.key().length() - 4)));
        assertFalse(saved.getKeyPrefix().contains(created.key()), "掩码不得包含完整明文");
        assertTrue(saved.isEnabled());
        assertNotNull(saved.getCreatedBy());
    }

    @Test
    void generatedKeysAreRandomAndUrlSafe() {
        String first = service.generateKey();
        String second = service.generateKey();

        assertEquals(47, first.length(), "elk_ + 43 字符 base64url");
        assertTrue(first.startsWith("elk_"));
        assertNotEquals(first, second);
        assertEquals(43, first.substring(4).length());
    }

    @Test
    void createRejectsBlankOrTooLongName() {
        BizException blank = assertThrows(BizException.class, () -> service.create("  "));
        assertTrue(blank.getMessage().contains("名称"));

        BizException tooLong = assertThrows(BizException.class, () -> service.create("x".repeat(65)));
        assertTrue(tooLong.getMessage().contains("64"));

        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    void authenticateValidKeyUpdatesLastUsedAndReturnsContext() {
        String plaintext = service.generateKey();
        ApiKey stored = entity(3L, plaintext, true);
        when(repository.findByKeyHash(ApiKeyService.sha256(plaintext))).thenReturn(Optional.of(stored));
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiKeyService.ApiKeyContext> context = service.authenticate(plaintext);

        assertTrue(context.isPresent());
        assertEquals(3L, context.get().id());
        assertEquals("ci-runner", context.get().name());
        assertEquals(stored.getKeyPrefix().substring(0, 12), context.get().prefix());
        assertNotNull(stored.getLastUsedAt(), "成功鉴权应更新 last_used_at");
    }

    @Test
    void authenticateRejectsMissingUnknownAndDisabledKeys() {
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("  ").isEmpty());

        String unknown = service.generateKey();
        when(repository.findByKeyHash(ApiKeyService.sha256(unknown))).thenReturn(Optional.empty());
        assertTrue(service.authenticate(unknown).isEmpty());

        String disabledPlain = service.generateKey();
        when(repository.findByKeyHash(ApiKeyService.sha256(disabledPlain)))
                .thenReturn(Optional.of(entity(4L, disabledPlain, false)));
        assertTrue(service.authenticate(disabledPlain).isEmpty(), "停用密钥必须拒绝");
        verify(repository, never()).save(any());
    }

    @Test
    void lastUsedUpdateFailureDoesNotBlockAuthentication() {
        String plaintext = service.generateKey();
        ApiKey stored = entity(3L, plaintext, true);
        when(repository.findByKeyHash(ApiKeyService.sha256(plaintext))).thenReturn(Optional.of(stored));
        when(repository.save(any(ApiKey.class))).thenThrow(new RuntimeException("db down"));

        assertTrue(service.authenticate(plaintext).isPresent(), "更新时间失败不应阻塞请求");
    }

    // ------------------------------------------------------------------ 列表 / 停用

    @Test
    void listExposesOnlyPrefixAndMask() {
        String plaintext = service.generateKey();
        when(repository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(entity(3L, plaintext, true)));

        ApiKeyService.ApiKeyView view = service.list().get(0);

        assertEquals(3L, view.id());
        assertEquals("ci-runner", view.name());
        assertEquals(plaintext.substring(0, 12), view.prefix());
        assertTrue(view.maskedKey().startsWith(view.prefix() + "..."));
        assertTrue(view.maskedKey().endsWith(plaintext.substring(plaintext.length() - 4)));
        assertFalse(view.maskedKey().contains(plaintext), "列表不得泄露完整密钥");
        assertTrue(view.enabled());
        assertEquals("admin", view.createdBy());
    }

    @Test
    void revokeDisablesKeyAndIsIdempotent() {
        ApiKey enabled = entity(3L, service.generateKey(), true);
        when(repository.findById(3L)).thenReturn(Optional.of(enabled));
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.ApiKeyView first = service.revoke(3L);
        ApiKeyService.ApiKeyView second = service.revoke(3L);

        assertFalse(first.enabled());
        assertFalse(second.enabled());
        assertFalse(enabled.isEnabled());
        verify(repository, times(1)).save(any(ApiKey.class));
        verify(repository, never()).delete(any());
    }

    @Test
    void revokeUnknownKeyIs404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BizException error = assertThrows(BizException.class, () -> service.revoke(99L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApiKeyService.sha256("abc"));
    }
}
