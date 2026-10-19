package com.echolint.integration.streaming;

import com.echolint.exception.BizException;

/**
 * 默认流式转写适配器（未实现）：任何调用都显式抛错，避免「静默无转写」。
 *
 * <p>接入真实供应商时实现 {@link StreamingTranscriptionProvider} 并覆盖本 Bean。</p>
 */
public class UnsupportedStreamingProvider implements StreamingTranscriptionProvider {

    public static final String ID = "unsupported";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public StreamSession start(StreamOptions options) {
        throw new BizException("流式转写未启用：请配置 app.asr.streaming.* 并实现 StreamingTranscriptionProvider");
    }
}
