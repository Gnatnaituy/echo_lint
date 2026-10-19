package com.echolint.service;

import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型调用重试（Batch 3a）：429/5xx 最多重试 2 次（其它错误不重试），最终失败抛 BizException。
 *
 * <p>用 JDK 自带 HttpServer 做真实 HTTP 桩，验证 WebClient 状态码分支（不引入新依赖）。</p>
 */
class AiModelCallerTest {

    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
    private static final String ERROR_BODY = "{\"error\":{\"message\":\"boom\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());

    private HttpServer server;
    private AiModelCaller caller;
    private ScreenModelService.ActiveModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Integer queued = statuses.poll();
            int status = queued == null ? 200 : queued;
            byte[] body = (status == 200 ? OK_BODY : ERROR_BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        WebClient client = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        model = new ScreenModelService.ActiveModel(
                "stub", "Stub", "http://127.0.0.1", "stub-model", "/chat/completions",
                true, true, null, Map.of(), 10, client, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO);
        // 退避缩短到 1ms，避免单测空等 2s
        caller = new AiModelCaller(objectMapper, 1, 1);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void succeedsOnFirstAttempt() {
        JsonNode response = caller.call(model, "{}", "语义复筛");

        assertEquals("ok", response.path("choices").path(0).path("message").path("content").asText());
        assertEquals(1, requestBodies.size());
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        statuses.add(500);
        statuses.add(503);

        JsonNode response = caller.call(model, "{\"model\":\"m\"}", "语义复筛");

        assertEquals("ok", response.path("choices").path(0).path("message").path("content").asText());
        assertEquals(3, requestBodies.size(), "失败 2 次 + 成功 1 次");
        assertTrue(requestBodies.get(0).contains("model"), "请求体应原样重发");
    }

    @Test
    void retriesTooManyRequests() {
        statuses.add(429);

        caller.call(model, "{}", "语义复筛");

        assertEquals(2, requestBodies.size());
    }

    @Test
    void givesUpAfterTwoRetriesAndPropagatesBizException() {
        statuses.add(500);
        statuses.add(500);
        statuses.add(500);

        BizException error = assertThrows(BizException.class,
                () -> caller.call(model, "{}", "语义复筛"));

        assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
        assertTrue(error.getMessage().contains("语义复筛"), error.getMessage());
        assertEquals(3, requestBodies.size(), "最多 3 次尝试（原始 + 2 次重试）");
    }

    @Test
    void doesNotRetryNonRetryableStatuses() {
        statuses.add(400);

        BizException error = assertThrows(BizException.class,
                () -> caller.call(model, "{}", "词挖掘"));

        assertTrue(error.getMessage().contains("HTTP 400"), error.getMessage());
        assertEquals(1, requestBodies.size(), "4xx（非 429）不重试");
    }

    @Test
    void extractErrorMessagePrefersJsonErrorField() {
        statuses.add(500);
        statuses.add(500);
        statuses.add(500);

        BizException error = assertThrows(BizException.class,
                () -> caller.call(model, "{}", "语义复筛"));

        assertTrue(error.getMessage().contains("boom"), error.getMessage());
    }
}
