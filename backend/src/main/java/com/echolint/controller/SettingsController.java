package com.echolint.controller;

import com.echolint.dto.ScreenModelRequest;
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
 * 系统设置：AI 复筛模型（语义复筛 + 敏感词挖掘）的查看、切换与连通性测试
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ScreenModelService screenModelService;

    /** 当前生效的模型 + 全部候选供应商 */
    @GetMapping("/screen-model")
    public ScreenModelService.View screenModel() {
        return screenModelService.snapshot();
    }

    /** 运行时切换（落库持久化，立即生效，无需重启） */
    @PutMapping("/screen-model")
    public ScreenModelService.View updateScreenModel(@Valid @RequestBody ScreenModelRequest request) {
        return screenModelService.switchTo(request.providerId(), request.model());
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
}
