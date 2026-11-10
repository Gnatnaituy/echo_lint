package com.echolint.asr;

import java.util.Locale;

/**
 * 语音转写 provider（ASR 选型，见 docs/asr-and-settings-refactor.md §2）。
 *
 * <p>{@link #value()} 是落库/接口契约值（{@code asr.provider} 与前端 {@code activeProvider}）， {@link #label()}
 * 是界面展示名，{@link #apiKeyEnv()} 是未在界面保存密钥时的环境变量兜底名。
 */
public enum AsrProviderType {

    /** 本机 whisper.cpp（OpenAI 兼容 /v1/audio/transcriptions，音频不出本机） */
    LOCAL("local", "本地 whisper.cpp", "WHISPER_API_KEY"),

    /** 阿里云百炼录音文件识别（DashScope 异步任务，按 token/时长计费） */
    DASHSCOPE("dashscope", "阿里云百炼", "DASHSCOPE_API_KEY");

    private final String value;
    private final String label;
    private final String apiKeyEnv;

    AsrProviderType(String value, String label, String apiKeyEnv) {
        this.value = value;
        this.label = label;
        this.apiKeyEnv = apiKeyEnv;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    public String apiKeyEnv() {
        return apiKeyEnv;
    }

    /** 宽松解析：忽略大小写与首尾空白；{@code null}/空串/非法值一律返回 {@code null} （视为「未设置」，由调用方决定默认值），不抛异常。 */
    public static AsrProviderType fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (AsrProviderType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
