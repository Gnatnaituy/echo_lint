package com.echolint.domain;

import java.util.Locale;

/**
 * 通话方向：呼入 / 呼出 / 未知。
 *
 * <p>历史录音没有该字段，统一按 {@link #UNKNOWN} 处理；上传与筛选时枚举名大小写不敏感。</p>
 */
public enum CallDirection {
    INBOUND("呼入"),
    OUTBOUND("呼出"),
    UNKNOWN("未知");

    private final String label;

    CallDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 解析调用方传入的方向：空值按 UNKNOWN；非法值抛出 IllegalArgumentException（全局异常处理为 400）。
     */
    public static CallDirection parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return CallDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知通话方向: " + raw.trim() + "（仅支持 INBOUND / OUTBOUND / UNKNOWN）");
        }
    }

    /** 筛选参数：空值表示不过滤（返回 null），非法值 400 */
    public static CallDirection parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw);
    }
}
