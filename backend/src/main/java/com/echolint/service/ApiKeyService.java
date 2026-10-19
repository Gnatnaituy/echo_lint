package com.echolint.service;

import com.echolint.entity.ApiKey;
import com.echolint.exception.BizException;
import com.echolint.repository.ApiKeyRepository;
import com.echolint.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 开放 API Key（Batch 4a）：生成、鉴权、列表（只回掩码）、停用。
 *
 * <p>密钥格式 {@code elk_} + 32 字节随机（base64url，无 padding）。明文只在创建响应返回一次，
 * 数据库只存 SHA-256（64 位小写 hex）；鉴权按哈希命中唯一索引，不做明文比较。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    static final String KEY_PREFIX = "elk_";
    static final int RANDOM_BYTES = 32;
    /** 列表展示用的前缀长度（含 elk_），不含末 4 位掩码 */
    static final int DISPLAY_PREFIX_LENGTH = 12;

    private final ApiKeyRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建密钥：返回一次明文（只此一次），落库仅哈希 + 前缀 + 掩码所需信息。
     */
    public CreatedApiKey create(String name) {
        String normalizedName = normalizeName(name);
        String plaintext = generateKey();
        String prefix = plaintext.substring(0, DISPLAY_PREFIX_LENGTH);
        ApiKey entity = new ApiKey();
        entity.setName(normalizedName);
        entity.setKeyPrefix(mask(prefix, plaintext));
        entity.setKeyHash(sha256(plaintext));
        entity.setEnabled(true);
        entity.setCreatedBy(SecurityUtils.currentUsername().orElse("system"));
        ApiKey saved = repository.save(entity);
        log.info("API Key 已创建 id={} name={} prefix={} by={}", saved.getId(), saved.getName(),
                prefix, saved.getCreatedBy());
        return new CreatedApiKey(saved.getId(), saved.getName(), plaintext, prefix, saved.getCreatedAt());
    }

    /**
     * 鉴权：按 SHA-256 查找启用中的密钥；成功时更新 last_used_at。
     *
     * <p>last_used_at 更新失败只 WARN，不影响本次请求（可用性优先）。</p>
     */
    public Optional<ApiKeyContext> authenticate(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            return Optional.empty();
        }
        String hash = sha256(rawKey.trim());
        Optional<ApiKey> found = repository.findByKeyHash(hash).filter(ApiKey::isEnabled);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        try {
            key.setLastUsedAt(LocalDateTime.now());
            repository.save(key);
        } catch (Exception e) {
            log.warn("更新 API Key 最近使用时间失败 id={}: {}", key.getId(), e.getMessage());
        }
        return Optional.of(new ApiKeyContext(key.getId(), key.getName(), prefixOf(key.getKeyPrefix())));
    }

    /** 全部密钥（只含前缀与末 4 位掩码，绝不返回明文/哈希） */
    public List<ApiKeyView> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(ApiKeyService::toView)
                .toList();
    }

    /** 停用（幂等）：已停用的再调用仍返回当前状态，便于界面刷新 */
    public ApiKeyView revoke(Long id) {
        ApiKey entity = repository.findById(id)
                .orElseThrow(() -> new BizException("API Key 不存在: " + id, HttpStatus.NOT_FOUND));
        if (entity.isEnabled()) {
            entity.setEnabled(false);
            entity = repository.save(entity);
            log.info("API Key 已停用 id={} name={} prefix={}", entity.getId(), entity.getName(),
                    prefixOf(entity.getKeyPrefix()));
        }
        return toView(entity);
    }

    // ------------------------------------------------------------------ 工具

    /** 生成明文密钥：elk_ + 32 字节随机 base64url（43 字符） */
    String generateKey() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("名称不能为空");
        }
        String value = name.trim();
        if (value.length() > 64) {
            throw new BizException("名称最长 64 个字符");
        }
        return value;
    }

    private static ApiKeyView toView(ApiKey entity) {
        String masked = entity.getKeyPrefix();
        return new ApiKeyView(
                entity.getId(),
                entity.getName(),
                prefixOf(masked),
                masked,
                entity.isEnabled(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getLastUsedAt());
    }

    /** 展示掩码：前缀 + "..." + 明文末 4 位（末 4 位在创建时写入，库中无完整明文） */
    static String mask(String prefix, String plaintext) {
        if (!StringUtils.hasText(plaintext) || plaintext.length() <= 4) {
            return prefix + "...****";
        }
        return prefix + "..." + plaintext.substring(plaintext.length() - 4);
    }

    private static String prefixOf(String masked) {
        if (!StringUtils.hasText(masked)) {
            return "";
        }
        int idx = masked.indexOf("...");
        return idx > 0 ? masked.substring(0, idx) : masked;
    }

    // ------------------------------------------------------------------ DTO

    /**
     * 创建响应：plaintext 只在这里出现一次，调用方必须自行保存。
     */
    public record CreatedApiKey(
            Long id,
            String name,
            String key,
            String prefix,
            LocalDateTime createdAt) {
    }

    /** 列表项：仅前缀 + 掩码，不含明文/哈希 */
    public record ApiKeyView(
            Long id,
            String name,
            String prefix,
            String maskedKey,
            boolean enabled,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt) {
    }

    /** 鉴权通过后的上下文（过滤器写入 request attribute，供控制器审计使用） */
    public record ApiKeyContext(Long id, String name, String prefix) {
    }
}
