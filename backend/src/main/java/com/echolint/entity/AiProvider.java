package com.echolint.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型供应商（Batch 3a）：语义复筛 / 敏感词挖掘 / RAG embedding 共用的兼容端点。
 *
 * <p>供应商从 application.yml 迁移到数据库后，可在系统设置页增删改、无需重启；
 * 启动时把 yml 中尚未落库的供应商加密导入（builtIn=true），**不删除**任何库内行。</p>
 *
 * <p>安全：API Key 只存 AES-GCM 密文（{@link #apiKeyCipher}）与末 4 位掩码，
 * 解密密钥来自环境变量 APP_SECRET_KEY（见 {@code SecretCipher}）。</p>
 */
@Entity
@Table(name = "ai_providers", indexes = {
        @Index(name = "idx_ai_provider_enabled", columnList = "enabled")
})
@Getter
@Setter
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 供应商标识（小写 slug，唯一；持久化在 app_settings.screen.provider） */
    @Column(name = "provider_key", nullable = false, unique = true, length = 64)
    private String providerKey;

    /** 界面展示名，缺省回退到 providerKey */
    @Column(length = 64)
    private String label;

    @Column(length = 255)
    private String baseUrl;

    /** chat completions 路径（阿里云: /chat/completions；DeepSeek: /chat/completions） */
    @Column(length = 255)
    private String chatPath;

    /** AES-GCM 密文（Base64(iv||ciphertext)）；未配置密钥时为空 */
    @Column(columnDefinition = "TEXT")
    private String apiKeyCipher;

    /** 末 4 位掩码，界面展示用（永不返回密文/明文） */
    @Column(length = 16)
    private String apiKeyMasked;

    @Column(length = 128)
    private String defaultModel;

    /** 候选模型 JSON 数组（仅下拉候选，允许手填未列出的模型） */
    @Column(columnDefinition = "TEXT")
    private String modelsJson;

    /** 是否支持 response_format={"type":"json_object"} */
    @Column(nullable = false)
    private boolean jsonMode = true;

    /** 输出上限；为空则不发送 max_tokens */
    private Integer maxTokens;

    /** 输入单价（USD / 1K tokens），默认 0 */
    @Column(name = "input_price_per_1k", nullable = false, precision = 12, scale = 6)
    private BigDecimal inputPricePer1k = BigDecimal.ZERO;

    /** 输出单价（USD / 1K tokens），默认 0 */
    @Column(name = "output_price_per_1k", nullable = false, precision = 12, scale = 6)
    private BigDecimal outputPricePer1k = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 由 yml 配置导入的内置供应商；内置项不可删除 */
    @Column(nullable = false)
    private boolean builtIn = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
