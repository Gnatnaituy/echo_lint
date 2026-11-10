package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.echolint.exception.BizException;
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
 * DashScope 五步链路 + WebClient 响应缓冲上限的本地假服务测试（B1）。
 *
 * <p>用 JDK 自带 {@link HttpServer} 起 loopback 假服务（不联网、不引新依赖），返回超过 WebClient 默认 256KB 缓冲的识别结果
 * JSON，验证不会抛 DataBufferLimitException；同时覆盖 未知 task_status 快速失败与错误消息不泄露临时 OSS 凭证。
 */
class DashScopeAsrProviderHttpTest {

    private static final int LARGE_SENTENCES = 3000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DashScopeAsrProvider provider = new DashScopeAsrProvider(objectMapper);
    private final List<HttpServer> servers = new ArrayList<>();

    @TempDir Path tempDir;

    private Path audioFile;

    @BeforeEach
    void setUp() throws IOException {
        audioFile = Files.write(tempDir.resolve("call.wav"), new byte[2048]);
    }

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
    }

    @Test
    void transcribesLargeResultJsonBeyondDefaultWebClientBuffer() throws Exception {
        String bigResult = bigResultJson();
        assertTrue(
                bigResult.getBytes(StandardCharsets.UTF_8).length > 256 * 1024,
                "结果 JSON 必须超过 WebClient 默认 256KB 缓冲，才能覆盖 B1 回归");

        HttpServer server = startServer();
        registerSuccessFlow(server, bigResult);
        server.start();

        TranscriptionService.ChannelTranscript transcript =
                provider.transcribe(config(server, 30), audioFile);

        assertEquals(LARGE_SENTENCES, transcript.segments().size());
        assertEquals("zh", transcript.language());
        assertEquals(600, transcript.durationSeconds());
        // 句级时间戳（毫秒 → 秒）保持正确
        assertEquals(0.0, transcript.segments().get(0).start(), 1e-9);
        assertEquals(0.09, transcript.segments().get(0).end(), 1e-9);
    }

    @Test
    void unknownTaskStatusFailsFastWithoutWaitingForTimeout() throws Exception {
        HttpServer server = startServer();
        int port = server.getAddress().getPort();
        registerOssUpload(server);
        json(server, "/api/v1/uploads", policyJson(port));
        json(server, "/api/v1/services/audio/asr/transcription", submitJson());
        json(
                server,
                "/api/v1/tasks/task-1",
                """
                {"output":{"task_id":"task-1","task_status":"SOMETHING_NEW"}}
                """);
        server.start();

        long start = System.currentTimeMillis();
        BizException error =
                assertThrows(
                        BizException.class,
                        () -> provider.transcribe(config(server, 30), audioFile));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(error.getMessage().contains("task_status"), error.getMessage());
        assertTrue(error.getMessage().contains("SOMETHING_NEW"), error.getMessage());
        assertTrue(elapsed < 5000, "未知状态应快速失败，不应等待轮询超时（实际 " + elapsed + "ms）");
    }

    @Test
    void failedTaskErrorIncludesCodeAndMessageButNotRawCredentials() throws Exception {
        HttpServer server = startServer();
        int port = server.getAddress().getPort();
        registerOssUpload(server);
        json(server, "/api/v1/uploads", policyJson(port));
        json(server, "/api/v1/services/audio/asr/transcription", submitJson());
        json(
                server,
                "/api/v1/tasks/task-1",
                """
                {"output":{"task_id":"task-1","task_status":"FAILED",
                  "results":[{"code":"InvalidFile.DownloadFailed","message":"下载文件失败",
                    "signature":"SECRET_SIGNATURE_VALUE","policy":"SECRET_POLICY_VALUE"}]},
                 "request_id":"req-1"}
                """);
        server.start();

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> provider.transcribe(config(server, 30), audioFile));
        String message = error.getMessage();

        assertTrue(message.contains("InvalidFile.DownloadFailed"), message);
        assertTrue(message.contains("下载文件失败"), message);
        assertFalse(message.contains("SECRET_SIGNATURE_VALUE"), message);
        assertFalse(message.contains("SECRET_POLICY_VALUE"), message);
    }

    @Test
    void policyHttpErrorIsSanitized() throws Exception {
        HttpServer server = startServer();
        // 上传凭证接口返回 401，响应体混入会泄露的临时凭证字段
        json(
                server,
                "/api/v1/uploads",
                """
                {"code":"InvalidApiKey","message":"Invalid API-key provided.","request_id":"req-9",
                 "policy":"SECRET_POLICY_VALUE","signature":"SECRET_SIGNATURE_VALUE"}
                """,
                401);
        server.start();

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> provider.transcribe(config(server, 30), audioFile));
        String message = error.getMessage();

        assertTrue(message.contains("HTTP 401"), message);
        assertTrue(message.contains("InvalidApiKey"), message);
        assertFalse(message.contains("SECRET_POLICY_VALUE"), message);
        assertFalse(message.contains("SECRET_SIGNATURE_VALUE"), message);
    }

    @Test
    void policy404EmptyBodyHintsAtApiRoot() throws Exception {
        HttpServer server = startServer();
        json(server, "/api/v1/uploads", "", 404);
        server.start();

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> provider.transcribe(config(server, 30), audioFile));
        String message = error.getMessage();

        assertTrue(message.contains("HTTP 404"), message);
        assertTrue(message.contains("域名根") || message.contains("/api/v1"), message);
    }

    // ---------------------------------------------------------------- 假服务组装

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        servers.add(server);
        return server;
    }

    private AsrProvider.Config config(HttpServer server, int timeoutSeconds) {
        return new AsrProvider.Config(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "sk-test-key",
                "qwen-audio-3.1-asr-flash-filetrans",
                "",
                List.of("zh"),
                timeoutSeconds);
    }

    /** 注册完整的成功链路：凭证 → OSS 上传 → 提交 → 轮询 → 结果下载 */
    private void registerSuccessFlow(HttpServer server, String resultJson) throws IOException {
        int port = server.getAddress().getPort();
        json(server, "/api/v1/uploads", policyJson(port));
        registerOssUpload(server);
        json(server, "/api/v1/services/audio/asr/transcription", submitJson());
        json(
                server,
                "/api/v1/tasks/task-1",
                String.format(
                        """
                        {"output":{"task_id":"task-1","task_status":"SUCCEEDED",
                          "results":[{"transcription_url":"http://127.0.0.1:%d/result.json"}]}}
                        """,
                        port));
        json(server, "/result.json", resultJson);
    }

    private void registerOssUpload(HttpServer server) {
        server.createContext(
                "/oss-upload",
                exchange -> {
                    drain(exchange);
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
    }

    private String policyJson(int port) {
        return String.format(
                """
                {"data":{
                  "policy":"p","signature":"s",
                  "upload_host":"http://127.0.0.1:%d/oss-upload",
                  "upload_dir":"asr-test","oss_access_key_id":"ak",
                  "x_oss_object_acl":"private","x_oss_forbid_overwrite":"true",
                  "max_file_size_mb":200}}
                """,
                port);
    }

    private String submitJson() {
        return """
        {"output":{"task_id":"task-1","task_status":"PENDING"}}
        """;
    }

    private void json(HttpServer server, String path, String body) throws IOException {
        json(server, path, body, 200);
    }

    private void json(HttpServer server, String path, String body, int status) throws IOException {
        server.createContext(path, exchange -> respond(exchange, status, body));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        drain(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void drain(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    /** 构造 >256KB 的结果 JSON（sentences + words，贴近真实长录音响应） */
    private String bigResultJson() throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root.putObject("properties");
        properties.put("original_duration_in_milliseconds", 600_000);
        ArrayNode transcripts = root.putArray("transcripts");
        ObjectNode transcript = transcripts.addObject();
        transcript.put("channel_id", 0);
        transcript.put("text", "长录音结果体回归测试");
        ArrayNode sentences = transcript.putArray("sentences");
        for (int i = 0; i < LARGE_SENTENCES; i++) {
            ObjectNode sentence = sentences.addObject();
            sentence.put("begin_time", i * 100L);
            sentence.put("end_time", i * 100L + 90);
            sentence.put("text", "这是第 " + i + " 句用于放大结果体以超过默认响应缓冲的测试文本。");
            sentence.put("sentence_id", i + 1);
            ArrayNode words = sentence.putArray("words");
            for (int w = 0; w < 6; w++) {
                ObjectNode word = words.addObject();
                word.put("begin_time", i * 100L + w * 10L);
                word.put("end_time", i * 100L + w * 10L + 10);
                word.put("text", "词" + w);
            }
        }
        return objectMapper.writeValueAsString(root);
    }
}
