package com.echolint.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密钥加密（Batch 3a）：AES-GCM 往返、随机 IV、篡改检测、掩码与开发默认密钥。
 */
class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("test-secret");

    @Test
    void encryptDecryptRoundtrip() {
        String plaintext = "sk-live-0123456789abcdef";

        String encrypted = cipher.encrypt(plaintext);

        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted, "密文不得包含明文");
        assertFalse(encrypted.contains(plaintext));
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void randomIvMakesCiphertextsDifferent() {
        String plaintext = "sk-same-key";

        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertNotEquals(first, second, "每次加密应使用随机 IV");
        assertEquals(plaintext, cipher.decrypt(first));
        assertEquals(plaintext, cipher.decrypt(second));
    }

    @Test
    void base64ThirtyTwoByteKeyIsUsedDirectly() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        SecretCipher base64Cipher = new SecretCipher(Base64.getEncoder().encodeToString(key));

        String encrypted = base64Cipher.encrypt("hello");

        assertEquals("hello", base64Cipher.decrypt(encrypted));
        assertEquals("hello", new SecretCipher(Base64.getEncoder().encodeToString(key)).decrypt(encrypted));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String encrypted = cipher.encrypt("sk-secret-value");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0x01);
        String tampered = Base64.getEncoder().encodeToString(raw);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(tampered));
        assertTrue(error.getMessage().contains("解密失败"), error.getMessage());
    }

    @Test
    void wrongMasterKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("sk-secret-value");
        SecretCipher other = new SecretCipher("another-secret");

        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void malformedCiphertextIsRejected() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("not-base64!!"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("AAAA"));
    }

    @Test
    void maskKeepsOnlyLastFourCharacters() {
        assertEquals("****1234", cipher.mask("sk-abcdefgh1234"));
        assertEquals("****", cipher.mask("abc"), "短密钥整体隐藏");
        assertNull(cipher.mask(null));
        assertNull(cipher.mask("  "));
    }

    @Test
    void nullHandling() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
        assertNull(cipher.decrypt(""));
    }

    @Test
    void missingConfiguredKeyFallsBackToDevSecretAndStillRoundtrips() {
        SecretCipher fallback = new SecretCipher("");

        String encrypted = fallback.encrypt("sk-dev");

        assertEquals("sk-dev", fallback.decrypt(encrypted));
        assertEquals("sk-dev", new SecretCipher(null).decrypt(encrypted));
    }
}
