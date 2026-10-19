package com.echolint.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 开放 API 密钥（Batch 4a）。
 *
 * <p>安全约定：明文只在创建响应里返回一次；库里只存 SHA-256 哈希与前缀/末 4 位掩码，
 * 任何日志、列表、审计都不得出现明文或完整哈希。</p>
 */
@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "idx_api_key_enabled", columnList = "enabled")
})
@Getter
@Setter
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 展示用掩码（前缀 + "..." + 明文末 4 位，如 elk_AbCdEfGh...WXYZ）。
     * 不含完整明文，不足以还原密钥；列表/审计只允许出现这个值。
     */
    @Column(name = "key_prefix", nullable = false, length = 24)
    private String keyPrefix;

    /** 明文密钥的 SHA-256（64 位小写 hex），唯一索引用于 O(1) 鉴权 */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** 停用后立即拒绝鉴权（不物理删除，保留审计线索） */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
