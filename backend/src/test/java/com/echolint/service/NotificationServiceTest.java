package com.echolint.service;

import com.echolint.config.NotifyProperties;
import com.echolint.domain.NotifyEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Webhook 通知：JSON 报文、HMAC 签名、禁用跳过、失败吞掉、设置视图不泄露密钥。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 起一个真实接收端，验证「发出去的字节」而不是 mock 调用，
 * 同时覆盖签名头是否按标准 HMAC-SHA256 计算。</p>
 */
class NotificationServiceTest {

    /** 接收端记录的一次请求 */
    private record Received(String signature, String contentType, String body) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseCode = new AtomicInteger(200);

    private HttpServer server;
    private String hookUrl;
    private NotificationService service;
    private NotifyProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        properties = new NotifyProperties();
        properties.setEnabled(true);
        properties.setWebhookUrl(hookUrl);
        service = new NotificationService(properties, objectMapper);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Received(
                exchange.getRequestHeaders().getFirst(NotificationService.SIGNATURE_HEADER),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(body, StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(responseCode.get(), -1);
        exchange.close();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private JsonNode lastPayload() throws Exception {
        assertEquals(1, received.size(), "接收端应收到且只收到一条通知");
        return objectMapper.readTree(received.get(0).body());
    }

    private static String expectedSignature(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testEndpointSendsSignedJsonPayload() throws Exception {
        properties.setWebhookSecret("testsecret");

        NotificationService.TestResult result = service.test();

        assertTrue(result.ok(), result.message());
        Received request = received.get(0);
        assertTrue(request.contentType().contains("application/json"), request.contentType());
        JsonNode payload = lastPayload();
        assertEquals("NOTIFY_TEST", payload.path("event").asText());
        assertTrue(payload.path("occurrenceTime").asText().startsWith("20"), "occurrenceTime 应为 ISO 时间");
        assertDoesNotThrow(() -> OffsetDateTime.parse(payload.path("occurrenceTime").asText()));
        assertFalse(payload.has("recordingId"), "测试通知没有关联录音");
        assertFalse(payload.has("link"));
        assertEquals("EchoLint 测试通知", payload.path("title").asText());
        assertFalse(payload.path("detail").asText().isBlank());
        assertEquals(expectedSignature("testsecret", request.body()), request.signature());
    }

    @Test
    void notifySendsEventPayloadWithRecordingId() throws Exception {
        service.notify(NotifyEvent.REVIEW_CONFIRMED_VIOLATION, 7L, "复核确认违规（辱骂）",
                "录音 #7 被确认违规", "/recordings/7");

        JsonNode payload = lastPayload();
        assertEquals("REVIEW_CONFIRMED_VIOLATION", payload.path("event").asText());
        assertEquals(7L, payload.path("recordingId").asLong());
        assertEquals("复核确认违规（辱骂）", payload.path("title").asText());
        assertEquals("录音 #7 被确认违规", payload.path("detail").asText());
        assertEquals("/recordings/7", payload.path("link").asText());
        assertNull(received.get(0).signature(), "未配置密钥时不应发送签名头");
    }

    @Test
    void disabledNotificationIsSkippedWithoutRequest() {
        properties.setEnabled(false);

        service.notify(NotifyEvent.RECORDING_FAILED, 3L, "失败", "detail", null);

        assertTrue(received.isEmpty(), "关闭时不应发起任何 HTTP 请求");
    }

    @Test
    void disabledButConfiguredTestStillProbesConnectivity() {
        properties.setEnabled(false);

        NotificationService.TestResult result = service.test();

        assertTrue(result.ok(), "测试按钮用于验证配置，即使总开关关闭也应尝试发送");
        assertEquals(1, received.size());
    }

    @Test
    void non2xxIsReportedAsFailureAndNotThrown() {
        responseCode.set(500);

        NotificationService.TestResult result = assertDoesNotThrow(service::test);

        assertFalse(result.ok());
        assertTrue(result.message().contains("500"), result.message());
        assertDoesNotThrow(() -> service.notify(NotifyEvent.REVIEW_BACKLOG, null, "t", "d", null));
    }

    @Test
    void unreachableEndpointFailsSoftlyWithoutLeakingSecrets() {
        NotifyProperties broken = new NotifyProperties();
        broken.setEnabled(true);
        broken.setWebhookUrl("http://127.0.0.1:1/hook?token=topsecret");
        broken.setWebhookSecret("s3cret");
        broken.setTimeoutSeconds(1);
        NotificationService brokenService = new NotificationService(broken, objectMapper);

        NotificationService.TestResult result = assertDoesNotThrow(brokenService::test);
        assertDoesNotThrow(() -> brokenService.notify(NotifyEvent.NOTIFY_TEST, null, "t", "d", null));

        assertFalse(result.ok());
        assertFalse(result.message().contains("topsecret"), "日志/返回值不能泄露 URL 中的密钥：" + result.message());
        assertFalse(result.message().contains("s3cret"), "日志/返回值不能泄露签名密钥：" + result.message());
    }

    @Test
    void invalidUrlSchemeIsRejected() {
        properties.setWebhookUrl("ftp://example.com/hook");

        NotificationService.TestResult result = service.test();

        assertFalse(result.ok());
        assertTrue(result.message().contains("http"), result.message());
        assertTrue(received.isEmpty());
    }

    @Test
    void settingsViewExposesFlagsAndEventsButNoUrlOrSecret() {
        properties.setWebhookSecret("s3cret");
        properties.setBacklogThreshold(12);

        NotificationService.SettingsView view = service.settings();

        assertTrue(view.enabled());
        assertTrue(view.urlConfigured());
        assertEquals(12, view.backlogThreshold());
        assertEquals(List.of("REVIEW_CONFIRMED_VIOLATION", "RECORDING_FAILED", "REVIEW_BACKLOG", "NOTIFY_TEST"),
                view.events());
        String rendered = view.toString();
        assertFalse(rendered.contains(hookUrl), "设置视图不能返回完整 Webhook URL");
        assertFalse(rendered.contains("s3cret"), "设置视图不能返回签名密钥");
    }

    @Test
    void maskUrlStripsQuerySecrets() {
        String masked = NotificationService.maskUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=very-secret");

        assertFalse(masked.contains("very-secret"), masked);
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?***", masked);
        assertEquals("(未配置)", NotificationService.maskUrl(null));
    }
}
