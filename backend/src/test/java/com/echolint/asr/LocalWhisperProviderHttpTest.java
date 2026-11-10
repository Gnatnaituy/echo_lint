package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.echolint.service.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 whisper.cpp 假服务测试：验证 verbose_json 超过 WebClient 默认 256KB 缓冲时仍能解析（B1）， 以及 / 探活对 401/403
 * 判鉴权失败、其它状态判可达。
 */
class LocalWhisperProviderHttpTest {

    private static final int LARGE_SEGMENTS = 3000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalWhisperProvider provider = new LocalWhisperProvider(objectMapper);
    private final List<HttpServer> servers = new ArrayList<>();

    @TempDir Path tempDir;

    private Path audioFile;

    @BeforeEach
    void setUp() throws IOException {
        audioFile = Files.write(tempDir.resolve("call.wav"), new byte[1024]);
    }

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
    }

    @Test
    void transcribesLargeVerboseJsonBeyondDefaultWebClientBuffer() throws Exception {
        String bigResult = bigVerboseJson();
        assertTrue(
                bigResult.getBytes(StandardCharsets.UTF_8).length > 256 * 1024,
                "verbose_json 必须超过 WebClient 默认 256KB 缓冲，才能覆盖 B1 回归");

        HttpServer server = startServer();
        server.createContext(
                "/v1/audio/transcriptions", exchange -> respond(exchange, 200, bigResult));
        server.start();

        TranscriptionService.ChannelTranscript transcript =
                provider.transcribe(config(server), audioFile);

        assertEquals(LARGE_SEGMENTS, transcript.segments().size());
        assertEquals("chinese", transcript.language());
        assertEquals(26, transcript.durationSeconds());
        assertEquals("hello", transcript.text());
        assertTrue(transcript.segments().get(0).text().contains("这是第 0 个分段"));
    }

    @Test
    void probeTreatsUnauthorizedAsAuthFailure() throws Exception {
        HttpServer server = startServer();
        server.createContext(
                "/",
                exchange -> respond(exchange, 401, "{\"error\":{\"message\":\"unauthorized\"}}"));
        server.start();

        AsrProvider.ConnectResult result = provider.test(config(server));

        assertFalse(result.ok(), "401 表示服务可达但鉴权失败，不能报 OK");
        assertTrue(result.message().contains("鉴权失败"), result.message());
        assertTrue(result.message().contains("401"), result.message());
    }

    @Test
    void probeTreatsNotFoundAsReachable() throws Exception {
        HttpServer server = startServer();
        server.createContext("/", exchange -> respond(exchange, 404, "not found"));
        server.start();

        AsrProvider.ConnectResult result = provider.test(config(server));

        assertTrue(result.ok(), "whisper.cpp 根路径 404 也说明服务可达");
        assertTrue(result.message().contains("404"), result.message());
    }

    // ---------------------------------------------------------------- 假服务/数据

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        servers.add(server);
        return server;
    }

    private AsrProvider.Config config(HttpServer server) {
        return new AsrProvider.Config(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "local",
                "whisper-1",
                "/v1/audio/transcriptions",
                List.of(),
                30);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 构造 >256KB 的 whisper verbose_json 响应 */
    private String bigVerboseJson() throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("text", "hello");
        root.put("language", "chinese");
        root.put("duration", 26.12);
        ArrayNode segments = root.putArray("segments");
        for (int i = 0; i < LARGE_SEGMENTS; i++) {
            ObjectNode segment = segments.addObject();
            segment.put("id", i);
            segment.put("start", (i * 0.01));
            segment.put("end", (i * 0.01 + 0.09));
            segment.put("text", "这是第 " + i + " 个分段用于放大 verbose_json 响应体以覆盖默认缓冲。");
        }
        return objectMapper.writeValueAsString(root);
    }
}
