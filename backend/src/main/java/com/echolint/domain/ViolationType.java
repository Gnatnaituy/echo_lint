package com.echolint.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

/**
 * 违规类型（AI 复筛输出编码）
 */
@Getter
@RequiredArgsConstructor
public enum ViolationType {
    NONE("无"),
    INSULT("辱骂"),
    DISCRIMINATION("歧视"),
    THREAT("威胁"),
    HARASSMENT("骚扰"),
    SEXUAL("色情骚扰"),
    FRAUD("诈骗诱导"),
    PRIVACY("隐私泄露"),
    OTHER("其他");

    private final String label;

    @JsonCreator
    public static ViolationType fromCode(String code) {
        if (code == null) {
            return NONE;
        }
        try {
            return ViolationType.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}