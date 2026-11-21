package com.echolint.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.Test;

/**
 * W3C traceparent 解析与续接：合法串解析为 remote parent、非法串（长度/版本/HEX/全零）宁可不续接、 无上下文时各入口安全返回 null / no-op
 * span。
 */
class TraceContinuationTest {

    private static final String VALID_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void parsesValidTraceparentAsRemoteParent() {
        SpanContext ctx = TraceContinuation.parse(VALID_TRACEPARENT);
        assertNotNull(ctx);
        assertTrue(ctx.isValid());
        assertTrue(ctx.isRemote());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
        assertEquals("00f067aa0ba902b7", ctx.getSpanId());
        assertTrue(ctx.getTraceFlags().isSampled());
    }

    @Test
    void unsampledFlagsPropagate() {
        SpanContext ctx =
                TraceContinuation.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertNotNull(ctx);
        assertFalse(ctx.getTraceFlags().isSampled());
    }

    @Test
    void rejectsMalformedTraceparent() {
        // 长度不足 / 分隔符错位 / 非 00 版本 / 非 HEX / 全零 traceId / 全零 spanId
        assertNull(TraceContinuation.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7"));
        assertNull(
                TraceContinuation.parse("004bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertNull(
                TraceContinuation.parse("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertNull(
                TraceContinuation.parse("00-4bf92f3577b34da6a3ce929d0e0e47zz-00f067aa0ba902b7-01"));
        assertNull(
                TraceContinuation.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        assertNull(
                TraceContinuation.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"));
    }

    @Test
    void nullsAndBlanksAreNotContinued() {
        assertNull(TraceContinuation.parse(null));
        assertNull(TraceContinuation.startPipelineSpan(OpenTelemetry.noop(), null, 1L));
        assertNull(TraceContinuation.startPipelineSpan(OpenTelemetry.noop(), "  ", 1L));
    }

    @Test
    void malformedStoredValueFallsBackToNull() {
        // 落库值损坏（手写 SQL / 位截断）：跳过续接而不是连错 trace
        assertNull(TraceContinuation.startPipelineSpan(OpenTelemetry.noop(), "garbage", 1L));
    }

    @Test
    void noActiveSpanCapturesNullTraceparent() {
        // 单测线程没有 OTel current span：捕获入口安全返回 null（生产同场景为异步入队）
        assertNull(TraceContinuation.currentTraceparent());
    }

    @Test
    void validTraceparentStartsSpan() {
        // noop SDK 下 startSpan 返回 invalid span（可安全 makeCurrent/end），但不是 Java null
        Span span =
                TraceContinuation.startPipelineSpan(OpenTelemetry.noop(), VALID_TRACEPARENT, 1L);
        assertNotNull(span);
        span.end();
    }
}
