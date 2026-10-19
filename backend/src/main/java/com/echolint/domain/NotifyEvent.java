package com.echolint.domain;

/**
 * Webhook 通知事件类型（固定集合，通过事件名与接收端约定）。
 *
 * <p>Batch 2b-2：运营告警只覆盖四类事件，避免过度设计；
 * 事件名会原样出现在 JSON 的 {@code event} 字段与 {@code GET /api/settings/notifications} 的
 * {@code events} 列表中。</p>
 */
public enum NotifyEvent {

    /** 人工复核最终确认违规 */
    REVIEW_CONFIRMED_VIOLATION("复核确认违规"),

    /** 稽核流水线处理失败 */
    RECORDING_FAILED("录音处理失败"),

    /** 每日待复核积压超过阈值 */
    REVIEW_BACKLOG("待复核积压"),

    /** 系统设置页的手工测试通知 */
    NOTIFY_TEST("测试通知");

    private final String label;

    NotifyEvent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
