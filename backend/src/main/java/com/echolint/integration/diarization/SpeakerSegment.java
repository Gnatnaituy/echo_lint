package com.echolint.integration.diarization;

/**
 * 说话人分离片段：说话人标签 + 起止秒数（相对音频开头）。
 * 与转写分段合并时按时间区间重叠度匹配（见 docs/integrations.md）。
 */
public record SpeakerSegment(String speaker, double startSeconds, double endSeconds) {
}
