package com.echolint.service;

import com.echolint.asr.AsrProvider;
import com.echolint.asr.AsrProviderType;
import com.echolint.asr.AsrSettingsService;
import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音转写入口：按运行时配置分发到对应 provider（本地 whisper.cpp / 阿里云百炼）。
 *
 * <p>单次调用只负责一路音频；双声道拆 L/R 后顺序调用两次的编排仍由 {@link AudioTranscriptionService} 负责（契约见
 * docs/asr-and-settings-refactor.md §2.3： 本类保留类名、{@link ChannelTranscript} 与 {@link
 * #transcribe(Path)} 签名）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final AsrSettingsService asrSettings;
    private final List<AsrProvider> asrProviders;

    /**
     * @param text provider 返回的完整文本
     * @param segments 分段（未带说话人/声道，由上层补齐）
     * @param duration 时长（秒）
     */
    public record ChannelTranscript(
            String text, String language, int durationSeconds, List<TranscriptSegment> segments) {}

    public ChannelTranscript transcribe(Path audioFile) {
        AsrSettingsService.Resolved resolved = asrSettings.current();
        if (!resolved.ready()) {
            throw new BizException(resolved.unreadyMessage());
        }
        AsrProvider provider = providerOf(resolved.type());
        long start = System.currentTimeMillis();
        ChannelTranscript transcript = provider.transcribe(resolved.config(), audioFile);
        log.info(
                "{} 转写完成：{} 秒，语言={}，分段 {} 段，文本长度={}（耗时 {}ms）",
                resolved.type().label(),
                transcript.durationSeconds(),
                transcript.language(),
                transcript.segments().size(),
                transcript.text().length(),
                System.currentTimeMillis() - start);
        return transcript;
    }

    private AsrProvider providerOf(AsrProviderType type) {
        for (AsrProvider provider : asrProviders) {
            if (provider.type() == type) {
                return provider;
            }
        }
        // 正常不会发生（provider 均为 @Service），防御性报错避免 NPE
        throw new BizException("语音转写 provider 未实现: " + type.value());
    }
}
