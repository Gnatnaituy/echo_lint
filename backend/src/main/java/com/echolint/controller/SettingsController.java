package com.echolint.controller;

import com.echolint.domain.AuditAction;
import com.echolint.dto.ScreenModelRequest;
import com.echolint.service.AuditService;
import com.echolint.service.NotificationService;
import com.echolint.service.ScreenModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置：AI 复筛模型（语义复筛 + 敏感词挖掘）的查看、切换与连通性测试；
 * Batch 2b-2 起增加 Webhook 通知配置查看与测试发送（均仅 ADMIN，见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ScreenModelService screenModelService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /** 当前生效的模型 + 全部候选供应商 */
    @GetMapping("/screen-model")
    public ScreenModelService.View screenModel() {
        return screenModelService.snapshot();
    }

    /** 运行时切换（落库持久化，立即生效，无需重启） */
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
}
