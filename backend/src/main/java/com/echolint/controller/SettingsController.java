package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.dto.ModelEvaluationRequest;
import com.echolint.dto.ProviderRequest;
import com.echolint.dto.ProviderTestRequest;
import com.echolint.dto.ScreenModelRequest;
import com.echolint.service.AuditService;
import com.echolint.service.IntegrationStatusService;
import com.echolint.service.ModelEvaluationService;
import com.echolint.service.NotificationService;
import com.echolint.service.ProviderAdminService;
import com.echolint.service.ProviderRegistry.ProviderSnapshot;
import com.echolint.service.ScreenModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置：AI 复筛模型（语义复筛 + 敏感词挖掘）的查看、切换与连通性测试；
 * Batch 2b-2 起增加 Webhook 通知配置查看与测试发送；
 * Batch 3a 起增加 AI 供应商 CRUD（密钥只回掩码）与供应商连通性测试；
 * Batch 5 起增加集成适配器状态（多租户/SSO/流式 ASR/说话人分离，不含密钥）。
 * 除 GET /screen-model 外均仅 ADMIN（见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ScreenModelService screenModelService;
    private final ProviderAdminService providerAdminService;
    private final ModelEvaluationService modelEvaluationService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final IntegrationStatusService integrationStatusService;

    /** 当前生效的模型 + 全部候选供应商 */
    @GetMapping("/screen-model")
    public ScreenModelService.View screenModel() {
        return screenModelService.snapshot();
    }

    /** 运行时切换（落库持久化，立即生效，无需重启；未知/停用的供应商会被拒绝） */
    @PutMapping("/screen-model")
    public ScreenModelService.View updateScreenModel(@Valid @RequestBody ScreenModelRequest request) {
        ScreenModelService.View view = screenModelService.switchTo(request.providerId(), request.model());
        auditService.record(AuditAction.SETTINGS_UPDATE, "SETTINGS", "screen-model",
                "切换复筛模型为 " + view.providerId() + " · " + view.model());
        return view;
    }

    /** 连通性测试：请求体留空则测当前生效的模型 */
    @PostMapping("/screen-model/test")
    public ScreenModelService.TestResult testScreenModel(@RequestBody(required = false) ScreenModelRequest request) {
        if (request == null || !StringUtils.hasText(request.providerId())) {
            ScreenModelService.ActiveModel active = screenModelService.active();
            return screenModelService.test(active.providerId(), active.model());
        }
        return screenModelService.test(request.providerId(), request.model());
    }

    /**
     * 模型效果评测（Batch 3b-2，ADMIN）：用已标注语料跑一次离线评测，返回混淆矩阵与逐条结果。
     * 同步执行，样本数封顶 20，单条调用超时 60s；失败样本计入 failed 并在 results 中带 error。
     */
    @PostMapping("/screen-model/evaluate")
    public ModelEvaluationService.EvaluationResult evaluateScreenModel(
            @Valid @RequestBody ModelEvaluationRequest request) {
        ModelEvaluationService.EvaluationResult result = modelEvaluationService.evaluate(
                request.providerId(), request.model(), request.sampleSize());
        auditService.record(AuditAction.MODEL_EVALUATE, "SETTINGS", "screen-model",
                "模型评测 " + result.providerId() + " · " + result.model()
                        + "：样本 " + result.total() + "，有效 " + result.evaluated()
                        + "，失败 " + result.failed()
                        + "，accuracy=" + result.accuracy()
                        + "，precision=" + result.precision()
                        + "，recall=" + result.recall()
                        + "（耗时 " + result.latencyMs() + "ms）");
        return result;
    }

    // ------------------------------------------------------------------ AI 供应商（Batch 3a）

    /** 供应商列表（ADMIN）：密钥只回末 4 位掩码与是否已配置，绝不返回密文 */
    @GetMapping("/providers")
    public List<ProviderSnapshot> providers() {
        return providerAdminService.list();
    }

    @PostMapping("/providers")
    public ResponseEntity<ProviderSnapshot> createProvider(@Valid @RequestBody ProviderRequest request) {
        ProviderSnapshot created = providerAdminService.create(request);
        auditService.record(AuditAction.PROVIDER_CREATE, "PROVIDER", created.key(),
                "新增 AI 供应商 " + created.key() + "（" + created.baseUrl() + "，API Key "
                        + (created.apiKeyConfigured() ? "已配置" : "未配置") + "）");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/providers/{key}")
    public ProviderSnapshot updateProvider(@PathVariable String key,
                                           @Valid @RequestBody ProviderRequest request) {
        ProviderSnapshot updated = providerAdminService.update(key, request);
        auditService.record(AuditAction.PROVIDER_UPDATE, "PROVIDER", updated.key(),
                "更新 AI 供应商 " + updated.key() + "（" + (updated.enabled() ? "启用" : "停用")
                        + "，API Key " + (updated.apiKeyConfigured() ? "已配置" : "未配置") + "）");
        return updated;
    }

    @DeleteMapping("/providers/{key}")
    public ResponseEntity<Void> deleteProvider(@PathVariable String key) {
        providerAdminService.delete(key);
        auditService.record(AuditAction.PROVIDER_DELETE, "PROVIDER", key, "删除 AI 供应商 " + key);
        return ResponseEntity.noContent().build();
    }

    /** 供应商连通性测试（可选 model）；失败也返回 200 + ok=false，便于界面展示原因 */
    @PostMapping("/providers/{key}/test")
    public ScreenModelService.TestResult testProvider(@PathVariable String key,
                                                      @RequestBody(required = false) ProviderTestRequest request) {
        String model = request == null ? null : request.model();
        ScreenModelService.TestResult result = screenModelService.test(key, model);
        auditService.record(AuditAction.PROVIDER_TEST, "PROVIDER", key,
                "AI 供应商连通性测试" + (result.ok() ? "成功" : "失败")
                        + "（" + result.providerLabel() + " · " + result.model()
                        + "，耗时 " + result.latencyMs() + "ms）"
                        + (result.ok() ? "" : "：" + result.message()));
        return result;
    }

    // ------------------------------------------------------------------ Webhook 通知

    /** Webhook 通知设置（ADMIN）：返回 enabled/urlConfigured/积压阈值/固定事件列表，不返回密钥与完整 URL */
    @GetMapping("/notifications")
    public NotificationService.SettingsView notifications() {
        return notificationService.settings();
    }

    /** 发送测试 Webhook（ADMIN）：同步返回 {ok, latencyMs, message}，并记 NOTIFY_TEST 审计 */
    @PostMapping("/notifications/test")
    public NotificationService.TestResult testNotification() {
        NotificationService.TestResult result = notificationService.test();
        auditService.record(AuditAction.NOTIFY_TEST, "SETTINGS", "notifications",
                (result.ok() ? "测试通知发送成功" : "测试通知发送失败")
                        + "（耗时 " + result.latencyMs() + "ms）"
                        + (result.ok() ? "" : "：" + result.message()));
        return result;
    }

    // ------------------------------------------------------------------ 集成适配器（Batch 5）

    /**
     * 集成适配器状态（ADMIN）：多租户 / SSO / 流式 ASR / 说话人分离。
     * 只返回是否启用/配置、供应商名与 SSO 登录地址，不包含任何密钥或完整端点密钥参数。
     */
    @GetMapping("/integrations")
    public IntegrationStatusService.IntegrationsView integrations() {
        return integrationStatusService.status();
    }
}
