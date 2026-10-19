package com.echolint.integration.diarization;

import java.nio.file.Path;
import java.util.List;

/**
 * 说话人分离（diarization）适配器（Batch 5）。
 *
 * <p>仅单声道录音需要（双声道仍走现有左右声道拆分）。默认实现
 * {@link UnsupportedDiarizationProvider} 显式抛「未配置」；调用方应捕获异常并降级为
 * 「单说话人」而不是让整条流水线失败（见 docs/integrations.md 的降级策略）。</p>
 */
public interface DiarizationProvider {

    /** 适配器标识（如 pyannote / aliyun / unsupported） */
    String id();

    /** 是否已配置（endpoint + api-key 齐全）；状态接口只暴露该布尔值 */
    boolean configured();

    /**
     * 对整段音频做说话人分离。
     *
     * @param audioPath 服务器本地音频文件路径（不要接受外部 URL，避免 SSRF）
     * @return 按时间升序的说话人片段；说话人标签由适配器生成（SPEAKER_00…）
     * @throws com.echolint.exception.BizException 未启用/未配置/分离失败时
     */
    List<SpeakerSegment> diarize(Path audioPath);
}
