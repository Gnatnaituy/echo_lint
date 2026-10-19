package com.echolint.integration.streaming;

/**
 * 流式（实时）语音转写适配器（Batch 5）。
 *
 * <p>默认实现 {@link UnsupportedStreamingProvider} 显式抛「未配置」；
 * 本轮<b>不改变现有批量转写流程</b>，接入点见 docs/integrations.md。
 * 实现方负责连接管理、重连与超时，调用方按 session 生命周期使用。</p>
 */
public interface StreamingTranscriptionProvider {

    /** 适配器标识（如 funasr / deepgram / unsupported） */
    String id();

    /** 是否已配置（endpoint + api-key 齐全）；状态接口只暴露该布尔值 */
    boolean configured();

    /**
     * 开启一个流式会话。
     *
     * @throws com.echolint.exception.BizException 未启用/未配置/无法建立连接时
     */
    StreamSession start(StreamOptions options);

    /** 会话参数（由调用方在收到音频流元信息后确定） */
    record StreamOptions(String language, Integer sampleRate) {
    }

    /** 一次实时转写会话 */
    interface StreamSession extends AutoCloseable {

        /** 送入一段音频（编码/采样率由适配器与调用方约定，建议 16bit PCM/16kHz） */
        void send(byte[] audioChunk);

        /** 结束会话并返回最终转写文本（可能为空串，不得返回 null） */
        String finish();

        /** 释放底层连接；必须幂等，且不得抛出未捕获异常 */
        @Override
        void close();
    }
}
