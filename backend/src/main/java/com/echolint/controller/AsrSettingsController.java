package com.echolint.controller;

import com.echolint.asr.AsrProviderType;
import com.echolint.asr.AsrSettingsService;
import com.echolint.domain.AuditAction;
import com.echolint.exception.BizException;
import com.echolint.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语音转写设置（docs/asr-and-settings-refactor.md §2.2，全部仅 ADMIN；URL 级权限见 SecurityConfig）。
 *
 * <p>GET/PUT 承载 provider 切换与本地/百炼配置（API Key 只回掩码）；POST /test 为低成本连通性测试， 失败也返回 200 + ok=false
 * 便于界面展示。保存/切换写 {@link AuditAction#SETTINGS_UPDATE} 审计 （resourceType=SETTINGS、resourceKey=asr）。
 */
@RestController
@RequestMapping("/api/settings/asr")
@RequiredArgsConstructor
public class AsrSettingsController {

    private final AsrSettingsService asrSettingsService;
    private final AuditService auditService;

    /** 连通性测试请求体：provider 可省略（省略=测当前生效） */
    public record TestRequest(String provider) {}

    @GetMapping
    public AsrSettingsService.View view() {
        return asrSettingsService.view();
    }

    @PutMapping
    public AsrSettingsService.View update(
            @RequestBody(required = false) AsrSettingsService.UpdateRequest request) {
        AsrSettingsService.View view = asrSettingsService.update(request);
        auditService.record(
                AuditAction.SETTINGS_UPDATE,
                "SETTINGS",
                "asr",
                "更新语音转写设置：provider="
                        + view.activeProvider()
                        + "，超时="
                        + view.timeoutSeconds()
                        + "s，本地就绪="
                        + view.local().ready()
                        + "，百炼就绪="
                        + view.dashscope().ready());
        return view;
    }

    /** 连通性测试：本地探活 / 百炼只取上传凭证（均不产生识别费用） */
    @PostMapping("/test")
    public AsrSettingsService.TestResult test(@RequestBody(required = false) TestRequest request) {
        String rawProvider = request == null ? null : request.provider();
        AsrProviderType provider = AsrProviderType.fromValue(rawProvider);
        if (StringUtils.hasText(rawProvider) && provider == null) {
            throw new BizException(
                    "未知的语音转写 provider: " + rawProvider.trim() + "（可选 local / dashscope）");
        }
        return asrSettingsService.test(provider);
    }
}
