package com.echolint.service;

import com.echolint.config.NotifyProperties;
import com.echolint.domain.NotifyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 通知发送（Batch 2b-2）。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>发送异步且<b>只记 warn、绝不抛异常</b> —— 通知是旁路能力，不能拖垮复核/流水线主流程；</li>
 *   <li>接收地址只来自配置，不提供运行时改写接口（避免 SSRF）；</li>
 *   <li>配置了密钥时，按 {@code X-EchoLint-Signature: sha256=<hex(HMAC-SHA256(body))>} 签名；</li>
 *   <li>日志里只出现脱敏后的 URL（去掉 query/userinfo），密钥永不落日志。</li>
 * </ul>
 *
 * <p>JSON 结构：{@code {event, occurrenceTime, recordingId?, title, detail, link?}}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** HMAC 签名头（接收端据此校验报文未被篡改） */
    public static final String SIGNATURE_HEADER = "X-EchoLint-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ZoneId NOTIFY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_DETAIL_CHARS = 1000;

    private final NotifyProperties notifyProperties;
    private final ObjectMapper objectMapper;

    /** 系统设置页视图（GET /api/settings/notifications） */
    public record SettingsView(
            boolean enabled,
            boolean urlConfigured,
            int backlogThreshold,
            List<String> events) {
    }

    /** 手工测试结果（POST /api/settings/notifications/test） */
    public record TestResult(
            boolean ok,
            long latencyMs,
            String message) {
    }

    /** 单次投递结果，供同步测试接口与日志复用 */
    record DeliveryResult(boolean ok, long latencyMs, int statusCode, String message) {
    }

    /** 设置页视图：不返回任何密钥/完整 URL */
    public SettingsView settings() {
        return new SettingsView(
                notifyProperties.isEnabled(),
                notifyProperties.httpUrlConfigured(),
                notifyProperties.effectiveBacklogThreshold(),
                listOfEvents());
    }

    /**
     * 异步发送通知。异常与失败都在内部消化，调用方无需 try/catch。
     */
    @Async("notificationExecutor")
    public void notify(NotifyEvent event, Long recordingId, String title, String detail, String link) {
        if (!notifyProperties.isEnabled()) {
            log.debug("通知未启用，跳过事件 {}", event);
            return;
        }
        if (!notifyProperties.httpUrlConfigured()) {
            log.warn("通知已开启但未配置可用的 webhook-url（http/https），跳过事件 {}", event);
            return;
        }
        try {
            DeliveryResult result = deliver(event, recordingId, title, detail, link);
            if (!result.ok()) {
                log.warn("Webhook 通知未送达 event={} url={}：{}", event,
                        maskUrl(notifyProperties.getWebhookUrl()), result.message());
            }
        } catch (Exception e) {
            log.warn("Webhook 通知发送异常 event={} url={}：{}", event,
                    maskUrl(notifyProperties.getWebhookUrl()), e.getMessage());
        }
    }

    /**
     * 同步发送一条测试通知（系统设置页需要即时结果），返回 {ok, latencyMs, message}。
     * 即使总开关关闭也会尝试投递 —— 测试按钮的目的就是验证 URL/签名配置是否可用。
     */
    public TestResult test() {
        long start = System.currentTimeMillis();
        if (!notifyProperties.httpUrlConfigured()) {
            return new TestResult(false, 0,
                    "未配置可用的 webhook-url（需为 http/https 地址），请设置 APP_NOTIFY_WEBHOOK_URL");
        }
        try {
            DeliveryResult result = deliver(NotifyEvent.NOTIFY_TEST, null,
                    "EchoLint 测试通知", "这是一条来自 EchoLint「系统设置」的测试 Webhook，可用于校验签名与报文格式。", null);
            String message = result.ok()
                    ? "发送成功（HTTP " + result.statusCode() + "，耗时 " + result.latencyMs() + "ms）"
                    : "发送失败：" + result.message();
            return new TestResult(result.ok(), System.currentTimeMillis() - start, message);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new TestResult(false, System.currentTimeMillis() - start, "发送异常：" + reason);
        }
    }

    // ------------------------------------------------------------------ 发送

    private DeliveryResult deliver(NotifyEvent event, Long recordingId, String title, String detail, String link) {
        URI uri = parseUri(notifyProperties.getWebhookUrl().trim());
        if (uri == null) {
            return new DeliveryResult(false, 0, 0, "webhook-url 不是合法的 http/https 地址");
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload(event, recordingId, title, detail, link));
        } catch (Exception e) {
            return new DeliveryResult(false, 0, 0, "通知报文序列化失败：" + e.getMessage());
        }

        int timeout = notifyProperties.effectiveTimeoutSeconds();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "EchoLint-Webhook/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String secret = notifyProperties.getWebhookSecret();
        if (StringUtils.hasText(secret)) {
            builder.header(SIGNATURE_HEADER, "sha256=" + hmacSha256Hex(secret.trim(), body));
        }

        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            long cost = System.currentTimeMillis() - start;
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.info("Webhook 通知已发送 event={} url={} status={} 耗时={}ms",
                        event, maskUrl(notifyProperties.getWebhookUrl()), status, cost);
                return new DeliveryResult(true, cost, status, "HTTP " + status);
            }
            return new DeliveryResult(false, cost, status, "接收端返回 HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long cost = System.currentTimeMillis() - start;
            return new DeliveryResult(false, cost, 0, "发送被中断");
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String reason = e.getClass().getSimpleName()
                    + (StringUtils.hasText(e.getMessage()) ? ": " + e.getMessage() : "");
            return new DeliveryResult(false, cost, 0, reason);
        }
    }

    private Map<String, Object> payload(NotifyEvent event, Long recordingId, String title, String detail, String link) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event.name());
        payload.put("occurrenceTime", OffsetDateTime.now(NOTIFY_ZONE).format(TIME_FORMAT));
        if (recordingId != null) {
            payload.put("recordingId", recordingId);
        }
        payload.put("title", StringUtils.hasText(title) ? title : event.label());
        payload.put("detail", truncate(detail == null ? "" : detail, MAX_DETAIL_CHARS));
        if (StringUtils.hasText(link)) {
            payload.put("link", link.trim());
        }
        return payload;
    }

    private static URI parseUri(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && StringUtils.hasText(uri.getHost())) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            log.warn("webhook-url 非法：{}", maskUrl(url));
        }
        return null;
    }

    /** HMAC-SHA256(secret, body) 的小写 hex */
    static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 签名失败", e);
        }
    }

    /**
     * 脱敏 URL：只保留 scheme://host[:port]/path，query 一律打码。
     * 企微/钉钉的 webhook 通常把密钥放在 query 里，绝不能原样进日志。
     */
    static String maskUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "(未配置)";
        }
        try {
            URI uri = URI.create(url.trim());
            if (!StringUtils.hasText(uri.getHost())) {
                return "(非法 URL)";
            }
            StringBuilder masked = new StringBuilder();
            masked.append(uri.getScheme() == null ? "http" : uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                masked.append(':').append(uri.getPort());
            }
            if (StringUtils.hasText(uri.getPath())) {
                masked.append(uri.getPath());
            }
            if (uri.getQuery() != null) {
                masked.append("?***");
            }
            return masked.toString();
        } catch (Exception e) {
            return "(非法 URL)";
        }
    }

    private static List<String> listOfEvents() {
        return Arrays.stream(NotifyEvent.values()).map(Enum::name).toList();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
