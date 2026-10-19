package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.dto.ApiKeyCreateRequest;
import com.echolint.service.ApiKeyService;
import com.echolint.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开放 API 密钥管理（Batch 4a，仅 ADMIN，见 SecurityConfig）。
 *
 * <p>创建响应包含一次明文 key；列表/审计只出现前缀与掩码。DELETE 为停用而非物理删除。</p>
 */
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;

    /** 密钥列表（前缀 + 末 4 位掩码，不含明文/哈希） */
    @GetMapping
    public List<ApiKeyService.ApiKeyView> list() {
        return apiKeyService.list();
    }

    /** 创建密钥：明文 key 只在本次响应返回一次 */
    @PostMapping
    public ResponseEntity<ApiKeyService.CreatedApiKey> create(
            @Valid @RequestBody ApiKeyCreateRequest request) {
        ApiKeyService.CreatedApiKey created = apiKeyService.create(request.name());
        auditService.record(AuditAction.API_KEY_CREATE, "API_KEY", created.id(),
                "创建 API Key " + created.name() + "（prefix " + created.prefix() + "）");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 停用密钥（幂等）：停用后使用该 key 的开放 API 请求立即 401 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        ApiKeyService.ApiKeyView view = apiKeyService.revoke(id);
        auditService.record(AuditAction.API_KEY_REVOKE, "API_KEY", view.id(),
                "停用 API Key " + view.name() + "（prefix " + view.prefix() + "）");
        return ResponseEntity.noContent().build();
    }
}
