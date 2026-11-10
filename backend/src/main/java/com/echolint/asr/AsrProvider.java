package com.echolint.asr;

import com.echolint.service.TranscriptionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 语音转写 provider 抽象：每次调用都使用 {@link AsrSettingsService} 解析出的当前配置， 因此管理员在「系统设置 → 语音转写」页改完立即生效，无需重启。
 *
 * <p>实现只负责「拿到配置 + 音频文件 → 单路转写结果」；双声道拆分/合并仍由 {@code AudioTranscriptionService} 编排（见
 * docs/asr-and-settings-refactor.md §2.3）。
 */
public interface AsrProvider {

    AsrProviderType type();

    /**
     * 转写单路音频文件。
     *
     * @param config 当前生效配置（apiKey 已解密；本地/云端共用同一记录）
     * @param audioFile 待转写音频（临时文件，调用方负责清理）
     * @return 与既有 {@link TranscriptionService.ChannelTranscript} 完全一致的结构，上层无需感知 provider
     */
    TranscriptionService.ChannelTranscript transcribe(Config config, Path audioFile);

    /**
     * 低成本连通性测试（不产生识别费用）：本地探测服务可达，百炼验证 Key/模型（只取上传凭证）。 失败不抛异常，通过 {@code ok=false + message}
     * 返回，便于设置页直接展示。
     */
    ConnectResult test(Config config);

    /** 单次转写/测试所需的解析后配置：DB 优先、env 兜底，apiKey 已解密（只在进程内使用）。 */
    record Config(
            String baseUrl,
            String apiKey,
            String model,
            String audioPath,
            List<String> languageHints,
            int timeoutSeconds) {

        public boolean ready() {
            return StringUtils.hasText(baseUrl)
                    && StringUtils.hasText(apiKey)
                    && StringUtils.hasText(model);
        }
    }

    /** 连通性测试结果（provider 视角；provider/model/耗时由设置服务补齐） */
    record ConnectResult(boolean ok, String message) {}

    /** 未就绪说明（例：「未配置API Key（请在设置页录入 API Key）」）。已就绪返回空串。 */
    static String missingDescription(Config config) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(config.baseUrl())) {
            missing.add("服务地址");
        }
        if (!StringUtils.hasText(config.apiKey())) {
            missing.add("API Key");
        }
        if (!StringUtils.hasText(config.model())) {
            missing.add("模型");
        }
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder message = new StringBuilder("未配置").append(String.join("、", missing));
        if (!StringUtils.hasText(config.apiKey())) {
            message.append("（请在设置页录入 API Key）");
        }
        return message.toString();
    }

    /** 异常消息兜底：message 为空时用异常类型名，避免日志/失败原因为空 */
    static String errorMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message : error.getClass().getSimpleName();
    }
}
