package com.echolint.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AI 供应商密钥的对称加解密（Batch 3a）。
 *
 * <p>算法固定为 AES/GCM/NoPadding：每次加密生成 12 字节随机 IV，密文格式为
 * {@code Base64(iv || ciphertext || tag)}，同一明文两次加密结果不同。GCM 自带认证标签，
 * 被篡改的密文解密直接失败，不会静默返回脏数据。</p>
 *
 * <p>主密钥来源（{@code APP_SECRET_KEY} / {@code app.secret-key}）：</p>
 * <ul>
 *   <li>能按 Base64 解出 32 字节 → 直接作为 AES-256 密钥；</li>
 *   <li>其它非空字符串 → SHA-256 派生 32 字节；</li>
 *   <li>未配置 → 使用内置开发默认值并 WARN（生产必须配置，否则换密钥后旧密文无法解密）。</li>
 * </ul>
 *
 * <p><b>安全约定</b>：本类任何日志都不打印明文密钥或密文；数据库只存密文 + 末 4 位掩码。</p>
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** 未配置 APP_SECRET_KEY 时的开发默认主密钥（仅用于本地/演示，生产必须覆盖） */
    static final String DEV_FALLBACK_SECRET = "echolint-dev-only-secret-key-do-not-use-in-production";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secret-key:}") String configuredKey) {
        this.key = deriveKey(configuredKey);
    }

    private SecretKeySpec deriveKey(String configured) {
        if (!StringUtils.hasText(configured)) {
            log.warn("未配置 APP_SECRET_KEY —— AI 供应商密钥将使用内置开发默认主密钥加密；"
                    + "生产环境请通过环境变量 APP_SECRET_KEY 配置（Base64 32 字节或任意字符串），"
                    + "否则主密钥变更后旧密文无法解密");
            return sha256(DEV_FALLBACK_SECRET);
        }
        String value = configured.trim();
        byte[] decoded = tryBase64(value);
        if (decoded != null && decoded.length == KEY_BYTES) {
            return new SecretKeySpec(decoded, KEY_ALGORITHM);
        }
        return sha256(value);
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SecretKeySpec sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(value.getBytes(StandardCharsets.UTF_8)), KEY_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 加密任意明文。{@code null} 原样返回（表示「未配置密钥」）；空字符串也会被正常加密。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密钥加密失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 解密 {@link #encrypt(String)} 产生的密文。
     *
     * @throws IllegalStateException 密文格式不正确、被篡改或主密钥已变更（消息不含密文/密钥）
     */
    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            return null;
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(ciphertext.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("密文格式不正确", e);
        }
        if (combined.length <= IV_BYTES) {
            throw new IllegalStateException("密文长度不足");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密文解密失败（密文被篡改，或 APP_SECRET_KEY 已变更）", e);
        }
    }

    /**
     * 生成展示用掩码：保留末 4 位，其余隐藏；长度不足 5 位的密钥整体隐藏。
     */
    public String mask(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        String value = plaintext.trim();
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }
}
