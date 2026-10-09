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
 * AI 调用留痕：一次语义复筛 / 敏感词挖掘的完整请求与原始回复。
 *
 * 对一个合规审计产品来说，「AI 为什么这么判」本身就是需要留痕的审计信息 ——
 * 光存解析后的结论（recordings.aiResultJson）无法回答提示词喂了什么、模型原话是什么。
 * 每次（重新）处理都会追加一条，因此历史调用的完整轨迹可回溯。
 *
 * 安全：只存**请求体**（messages/temperature 等），不存 Authorization 头，密钥不会落库。
 */
@Entity
@Table(name = "ai_invocations", indexes = {
        @Index(name = "idx_ai_inv_recording", columnList = "recordingId")
})
@Getter
@Setter
public class AiInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recordingId;

    /** SCREEN（语义复筛）/ MINING（敏感词挖掘） */
    @Column(length = 16)
    private String stage;

    @Column(length = 32)
    private String providerId;

    @Column(length = 64)
    private String providerLabel;

    @Column(length = 128)
    private String model;

    /** 实际请求的端点（不含密钥），便于确认打到哪个服务 */
    @Column(length = 255)
    private String endpoint;

    /**
     * 完整请求体 JSON。
     * MEDIUMTEXT 而非 TEXT：提示词含整段转写 + few-shot 语料，中文按 3 字节算很容易超过 TEXT 的 64KB。
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String requestJson;

    /** 模型返回的原始内容（成功）；失败时为空，原因见 errorMessage */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String responseJson;

    @Column(nullable = false)
    private Boolean success = Boolean.TRUE;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer latencyMs;

    /** 请求/回复字符数，列表页无需加载全文即可展示规模 */
    private Integer requestChars;

    private Integer responseChars;

    /** 是否降级转人工（复筛失败时为 true） */
    @Column(nullable = false)
    private Boolean degraded = Boolean.FALSE;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
