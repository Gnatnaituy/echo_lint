package com.echolint.util;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;

/**
 * 跨时间点的 trace 续接（W3C Trace Context）。
 *
 * <p>上传请求与约 5 秒后的调度执行是两个独立瞬间（HTTP 请求的 trace 早已结束）， micrometer 的 ThreadLocal 栈无法跨过这段间隔。这里把请求线程的 W3C
 * traceparent 落进 {@code pipeline_tasks.traceparent}，执行时用 {@code createFromRemoteParent} 恢复为 remote
 * parent，让「上传 → 转写 → DFA → AI 复筛」在 Jaeger 里连成同一条 trace。
 *
 * <p>解析从严：任何格式问题都放弃续接（宁可不连，不连错 trace），回退为无父 span 的独立 trace， 行为与未接入 tracing 时一致。
 */
@Slf4j
public final class TraceContinuation {

    /** W3C v0 traceparent 定长：{@code 00-{32 位 traceId}-{16 位 spanId}-{2 位 flags}} */
    private static final int TRACEPARENT_LENGTH = 55;

    private TraceContinuation() {}

    /**
     * 捕获当前线程的 W3C traceparent（如上传请求的处理 span）。
     *
     * @return 标准格式 traceparent；无有效 span（异步线程 / tracing 未启用 / 未采样）返回 {@code null}
     */
    public static String currentTraceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        // 手工拼 W3C v0 格式（SpanContext 无现成 toTraceParent）：00-{traceId}-{spanId}-{flags}
        return "00-"
                + context.getTraceId()
                + "-"
                + context.getSpanId()
                + "-"
                + context.getTraceFlags().asHex();
    }

    /**
     * 从落库的 traceparent 提取 traceId（任务视图透出，前端据此拼平台内嵌页的 Jaeger 深链 {@code /jaeger/trace/{traceId}}）。
     *
     * @return 32 位十六进制 traceId；traceparent 缺失/非法（历史数据 / tracing 未启用）返回 {@code null}
     */
    public static String traceIdOf(String traceparent) {
        SpanContext context = parse(traceparent);
        return context == null ? null : context.getTraceId();
    }

    /**
     * 用落库的 traceparent 恢复 remote parent 并开启「流水线执行」span（内部各阶段的 Observation span 会自动挂到它下面）。
     *
     * @return 已 start 的 span；traceparent 缺失/非法返回 {@code null}（调用方按无续接处理）
     */
    public static Span startPipelineSpan(
            OpenTelemetry openTelemetry, String traceparent, Long taskId) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        SpanContext parent = parse(traceparent);
        if (parent == null) {
            log.warn("任务 {} 的 traceparent 非法，跳过 trace 续接: {}", taskId, traceparent);
            return null;
        }
        return openTelemetry
                .getTracer("echolint")
                .spanBuilder("echolint.pipeline")
                // 显式 remote parent：不取线程 ThreadLocal（调度/执行线程的上下文与上传请求无关）
                .setParent(Context.root().with(Span.wrap(parent)))
                .setAttribute("task.id", taskId)
                .startSpan();
    }

    /** 解析 W3C v0 traceparent；格式非法（含 null/长度不足）返回 {@code null} */
    static SpanContext parse(String traceparent) {
        if (traceparent == null
                || traceparent.length() != TRACEPARENT_LENGTH
                || traceparent.charAt(2) != '-'
                || traceparent.charAt(35) != '-'
                || traceparent.charAt(52) != '-') {
            return null;
        }
        // W3C：非 00 版本必须整体忽略（未来版本语义未知）
        if (!"00".equals(traceparent.substring(0, 2))) {
            return null;
        }
        String traceId = traceparent.substring(3, 35);
        String spanId = traceparent.substring(36, 52);
        String flags = traceparent.substring(53, 55);
        if (!isHex(traceId) || !isHex(spanId) || !isHex(flags)) {
            return null;
        }
        // W3C：全零 traceId / spanId 非法
        if (isAllZero(traceId) || isAllZero(spanId)) {
            return null;
        }
        return SpanContext.createFromRemoteParent(
                traceId, spanId, TraceFlags.fromHex(flags, 0), TraceState.getDefault());
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
