package com.recordaudit.domain;

/**
 * 转写分段（双声道时带说话人与声道信息）
 *
 * @param speaker 说话人（如 坐席 / 客户），单声道为 null
 * @param channel 声道（L / R），单声道为 null
 * @param start   起始秒
 * @param end     结束秒
 * @param text    该段文本（已 trim，不含说话人前缀）
 */
public record TranscriptSegment(String speaker, String channel, double start, double end, String text) {

    public static TranscriptSegment of(String speaker, String channel, double start, double end, String text) {
        return new TranscriptSegment(speaker, channel, start, end, text == null ? "" : text.trim());
    }

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
