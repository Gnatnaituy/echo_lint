package com.echolint.integration.diarization;

import com.echolint.exception.BizException;

import java.nio.file.Path;
import java.util.List;

/**
 * 默认说话人分离适配器（未实现）：调用即显式抛错，由调用方降级为单说话人。
 */
public class UnsupportedDiarizationProvider implements DiarizationProvider {

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
    public List<SpeakerSegment> diarize(Path audioPath) {
        throw new BizException("说话人分离未启用：请配置 app.diarization.* 并实现 DiarizationProvider");
    }
}
