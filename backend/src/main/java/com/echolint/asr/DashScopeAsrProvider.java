package com.echolint.asr;

import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import com.echolint.service.TranscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 阿里云百炼录音文件识别（DashScope 异步任务）provider。
 *
 * <p>按 2026-11-10 实测通过的五步流程实现（见 docs/asr-and-settings-refactor.md §1）：
 *
 * <ol>
 *   <li>获取临时上传凭证：{@code GET {base}/api/v1/uploads?action=getPolicy&model=...}
 *   <li>直传 OSS：{@code POST {upload_host}}（multipart，字段名按 OSS 临时凭证约定）
 *   <li>提交异步任务：必须带 {@code X-DashScope-Async: enable} 与 {@code X-DashScope-OssResourceResolve:
 *       enable}，{@code file_urls} 用 {@code oss://{key}}
 *   <li>轮询任务状态（1.5s 间隔，预算见 {@code asr.timeout-seconds}）
 *   <li>下载结果 JSON（预签名 URL，<b>不能</b>附加 Authorization 头）
 * </ol>
 *
 * <p>说明：临时上传接口官方标注「限流 100 QPS，请勿用于生产高并发」，本项目量级可接受； 临时文件 48h 过期、结果 JSON 链接 24h
 * 过期，本流程即时消费不受影响。未来量大可换自有 OSS。
 *
 * <p><b>安全约定</b>：获取上传凭证/上传/提交/轮询/下载各步的错误消息只回 HTTP 状态 + DashScope code/message/request_id
 * 或缺失字段名，<b>绝不拼接原始响应体</b>——凭证响应里的 policy / signature / upload_host / oss_access_key_id 一旦写进录音
 * errorMessage，REVIEWER/VIEWER 都能看到。原始响应只在 DEBUG 日志按已脱敏方式（仅解析出的字段）打印。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeAsrProvider implements AsrProvider {

    static final String UPLOADS_PATH = "/api/v1/uploads";
    static final String TRANSCRIPTION_PATH = "/api/v1/services/audio/asr/transcription";
    static final String TASKS_PATH = "/api/v1/tasks/";

    /** 轮询间隔（§2.3） */
    static final Duration POLL_INTERVAL = Duration.ofMillis(1500);

    /**
     * WebClient 默认只允许 256KB 响应内存，长录音的结果 JSON（sentences + words）会超过该限制， 抛 DataBufferLimitException
     * 导致整条转写失败；统一放宽到 32MB（单次转写预算内足够）。
     */
    static final int MAX_IN_MEMORY_SIZE = 32 * 1024 * 1024;

    private static final Pattern XML_TAG = Pattern.compile("<([A-Za-z][A-Za-z0-9]*)>([^<]*)</\\1>");

    private final ObjectMapper objectMapper;

    @Override
    public AsrProviderType type() {
        return AsrProviderType.DASHSCOPE;
    }

    // ------------------------------------------------------------------ 转写主流程

    @Override
    public TranscriptionService.ChannelTranscript transcribe(Config config, Path audioFile) {
        if (!config.ready()) {
            throw new BizException("百炼转写未就绪：" + AsrProvider.missingDescription(config));
        }
        Duration budget = Duration.ofSeconds(config.timeoutSeconds());
        Instant deadline = Instant.now().plus(budget);
        WebClient client = buildClient(budget);
        try {
            UploadPolicy policy = fetchUploadPolicy(client, config, deadline, budget);
            String objectKey = uploadAudio(client, config, policy, audioFile, deadline, budget);
            String taskId = submitTask(client, config, objectKey, deadline, budget);
            String transcriptionUrl = awaitTranscription(client, config, taskId, deadline, budget);
            JsonNode result = downloadResult(client, transcriptionUrl, deadline, budget);
            TranscriptionService.ChannelTranscript transcript =
                    mapResult(result, config.languageHints());
            log.info(
                    "百炼录音文件识别完成：任务={}，模型={}，时长 {}s，分段 {} 段，文本长度 {}",
                    taskId,
                    config.model(),
                    transcript.durationSeconds(),
                    transcript.segments().size(),
                    transcript.text().length());
            return transcript;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("百炼转写调用异常: " + AsrProvider.errorMessage(e));
        }
    }

    @Override
    public ConnectResult test(Config config) {
        if (!StringUtils.hasText(config.apiKey())) {
            return new ConnectResult(false, "未配置 API Key：请在「系统设置 → 语音转写」页录入");
        }
        if (!StringUtils.hasText(config.baseUrl())) {
            return new ConnectResult(false, "未配置百炼服务地址");
        }
        if (!StringUtils.hasText(config.model())) {
            return new ConnectResult(false, "未配置百炼识别模型");
        }
        Duration timeout = Duration.ofSeconds(Math.min(config.timeoutSeconds(), 30));
        try {
            // 只取上传凭证：验证 Key 与模型可用，不产生识别费用
            JsonNode resp =
                    buildClient(timeout)
                            .get()
                            .uri(uploadPolicyUri(config))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::isError,
                                    clientResponse ->
                                            clientResponse
                                                    .bodyToMono(String.class)
                                                    .defaultIfEmpty("")
                                                    .map(
                                                            body ->
                                                                    new BizException(
                                                                            "HTTP "
                                                                                    + clientResponse
                                                                                            .statusCode()
                                                                                            .value()
                                                                                    + ": "
                                                                                    + describeErrorBody(
                                                                                            body))))
                            .bodyToMono(JsonNode.class)
                            .block(timeout);
            if (resp == null) {
                return new ConnectResult(false, "响应为空");
            }
            if (!StringUtils.hasText(resp.path("data").path("policy").asText(""))) {
                return new ConnectResult(false, "响应缺少上传凭证字段（data.policy）");
            }
            return new ConnectResult(true, "连通正常（上传凭证获取成功）");
        } catch (BizException e) {
            return new ConnectResult(false, e.getMessage());
        } catch (Exception e) {
            return new ConnectResult(false, "调用异常: " + AsrProvider.errorMessage(e));
        }
    }

    // ------------------------------------------------------------------ 五步流程

    /** 步骤 1：获取 OSS 临时上传凭证 */
    private UploadPolicy fetchUploadPolicy(
            WebClient client, Config config, Instant deadline, Duration budget) {
        JsonNode resp =
                block(
                        client.get()
                                .uri(uploadPolicyUri(config))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                                .retrieve()
                                .onStatus(
                                        HttpStatusCode::isError,
                                        clientResponse ->
                                                clientResponse
                                                        .bodyToMono(String.class)
                                                        .defaultIfEmpty("")
                                                        .map(
                                                                body ->
                                                                        new BizException(
                                                                                "百炼上传凭证获取失败 (HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                                .value()
                                                                                        + "): "
                                                                                        + describeErrorBody(
                                                                                                body))))
                                .bodyToMono(JsonNode.class),
                        deadline,
                        budget,
                        "获取上传凭证");
        return parseUploadPolicy(resp);
    }

    /** 解析并校验上传凭证（独立方法便于单元测试「缺失字段不泄露其它凭证值」） */
    UploadPolicy parseUploadPolicy(JsonNode resp) {
        if (resp == null) {
            throw new BizException("百炼上传凭证响应为空");
        }
        JsonNode data = resp.path("data");
        UploadPolicy policy =
                new UploadPolicy(
                        data.path("policy").asText(""),
                        data.path("signature").asText(""),
                        data.path("upload_host").asText(""),
                        data.path("upload_dir").asText(""),
                        data.path("oss_access_key_id").asText(""),
                        data.path("x_oss_object_acl").asText(""),
                        data.path("x_oss_forbid_overwrite").asText(""),
                        data.path("max_file_size_mb").asLong(0));
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(policy.uploadHost())) {
            missing.add("upload_host");
        }
        if (!StringUtils.hasText(policy.policy())) {
            missing.add("policy");
        }
        if (!StringUtils.hasText(policy.signature())) {
            missing.add("signature");
        }
        if (!StringUtils.hasText(policy.ossAccessKeyId())) {
            missing.add("oss_access_key_id");
        }
        if (!missing.isEmpty()) {
            // 只回缺失字段名：响应体其余字段是临时 OSS 凭证，绝不能进异常消息/日志
            log.debug("百炼上传凭证响应缺少字段（正文已省略，避免泄露临时凭证）：{}", missing);
            throw new BizException("百炼上传凭证响应缺少必要字段：" + String.join("、", missing));
        }
        log.debug(
                "百炼上传凭证获取成功：upload_dir={}，max_file_size_mb={}",
                policy.uploadDir(),
                policy.maxFileSizeMb());
        return policy;
    }

    /** 步骤 2：把音频直传 OSS，返回对象 key（后续用 oss://{key} 引用） */
    private String uploadAudio(
            WebClient client,
            Config config,
            UploadPolicy policy,
            Path audioFile,
            Instant deadline,
            Duration budget) {
        long fileSize;
        try {
            fileSize = Files.size(audioFile);
        } catch (IOException e) {
            throw new BizException("读取待转写音频失败: " + AsrProvider.errorMessage(e));
        }
        long maxBytes = policy.maxFileSizeMb() * 1024L * 1024L;
        if (maxBytes > 0 && fileSize > maxBytes) {
            throw new BizException(
                    "音频文件超过百炼临时上传上限（"
                            + policy.maxFileSizeMb()
                            + "MB，当前 "
                            + (fileSize / 1024 / 1024)
                            + "MB）");
        }
        String fileName = fileNameOf(audioFile);
        String objectKey = joinObjectKey(policy.uploadDir(), fileName);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("OSSAccessKeyId", policy.ossAccessKeyId());
        builder.part("Signature", policy.signature());
        builder.part("policy", policy.policy());
        builder.part("key", objectKey);
        builder.part("success_action_status", "200");
        if (StringUtils.hasText(policy.xOssObjectAcl())) {
            builder.part("x-oss-object-acl", policy.xOssObjectAcl());
        }
        if (StringUtils.hasText(policy.xOssForbidOverwrite())) {
            builder.part("x-oss-forbid-overwrite", policy.xOssForbidOverwrite());
        }
        builder.part("file", new FileSystemResource(audioFile.toFile())).filename(fileName);

        block(
                client.post()
                        .uri(uploadUri(policy.uploadHost()))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(builder.build()))
                        .retrieve()
                        .onStatus(
                                HttpStatusCode::isError,
                                clientResponse ->
                                        clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(
                                                        body ->
                                                                new BizException(
                                                                        "百炼音频上传失败 (HTTP "
                                                                                + clientResponse
                                                                                        .statusCode()
                                                                                        .value()
                                                                                + "): "
                                                                                + describeOssErrorBody(
                                                                                        body))))
                        .toBodilessEntity(),
                deadline,
                budget,
                "上传音频");
        return objectKey;
    }

    /** 步骤 3：提交异步识别任务（异步 + OSS 资源解析两个头缺一不可） */
    private String submitTask(
            WebClient client, Config config, String objectKey, Instant deadline, Duration budget) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("channel_id", List.of(0));
        if (!config.languageHints().isEmpty()) {
            parameters.put("language_hints", config.languageHints());
        }
        parameters.put("diarization_enabled", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("input", Map.of("file_urls", List.of("oss://" + objectKey)));
        body.put("parameters", parameters);

        JsonNode resp =
                block(
                        client.post()
                                .uri(transcriptionUri(config))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                                .header("X-DashScope-Async", "enable")
                                .header("X-DashScope-OssResourceResolve", "enable")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(body)
                                .retrieve()
                                .onStatus(
                                        HttpStatusCode::isError,
                                        clientResponse ->
                                                clientResponse
                                                        .bodyToMono(String.class)
                                                        .defaultIfEmpty("")
                                                        .map(
                                                                err ->
                                                                        new BizException(
                                                                                "百炼识别任务提交失败 (HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                                .value()
                                                                                        + "): "
                                                                                        + describeErrorBody(
                                                                                                err))))
                                .bodyToMono(JsonNode.class),
                        deadline,
                        budget,
                        "提交识别任务");
        if (resp == null) {
            throw new BizException("百炼识别任务提交响应为空");
        }
        JsonNode output = resp.path("output");
        String taskId = output.path("task_id").asText("");
        if (!StringUtils.hasText(taskId)) {
            throw new BizException("百炼识别任务提交失败：" + describeError(resp));
        }
        log.debug("百炼识别任务已提交：task_id={}，初始状态={}", taskId, output.path("task_status").asText(""));
        return taskId;
    }

    /** 步骤 4：轮询任务状态直到成功/失败/超时；未知状态快速失败，不空等超时 */
    private String awaitTranscription(
            WebClient client, Config config, String taskId, Instant deadline, Duration budget) {
        while (true) {
            JsonNode resp =
                    block(
                            client.get()
                                    .uri(taskUri(config, taskId))
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                                    .retrieve()
                                    .onStatus(
                                            HttpStatusCode::isError,
                                            clientResponse ->
                                                    clientResponse
                                                            .bodyToMono(String.class)
                                                            .defaultIfEmpty("")
                                                            .map(
                                                                    body ->
                                                                            new BizException(
                                                                                    "百炼识别任务查询失败"
                                                                                        + " (HTTP "
                                                                                            + clientResponse
                                                                                                    .statusCode()
                                                                                                    .value()
                                                                                            + "): "
                                                                                            + describeErrorBody(
                                                                                                    body))))
                                    .bodyToMono(JsonNode.class),
                            deadline,
                            budget,
                            "等待识别结果（task_id=" + taskId + "）");
            if (resp == null) {
                throw new BizException("百炼识别任务查询响应为空（task_id=" + taskId + "）");
            }
            JsonNode output = resp.path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                String url = output.path("results").path(0).path("transcription_url").asText("");
                if (!StringUtils.hasText(url)) {
                    throw new BizException("百炼识别成功但未返回结果地址（task_id=" + taskId + "）");
                }
                return url;
            }
            if ("FAILED".equalsIgnoreCase(status)
                    || "CANCELED".equalsIgnoreCase(status)
                    || "CANCELLED".equalsIgnoreCase(status)) {
                throw new BizException("百炼转写失败（task_id=" + taskId + "）：" + describeError(resp));
            }
            if (!"PENDING".equalsIgnoreCase(status) && !"RUNNING".equalsIgnoreCase(status)) {
                // 空/未知状态继续等只会拖到总预算耗尽，快速失败把真实状态带回失败原因
                throw new BizException(
                        "百炼识别任务状态未知（task_id=" + taskId + "，task_status=" + status + "）");
            }
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.compareTo(POLL_INTERVAL) <= 0) {
                throw timeout(budget, "等待识别结果（task_id=" + taskId + "，最后状态=" + status + "）");
            }
            sleep(POLL_INTERVAL);
        }
    }

    /** 步骤 5：下载结果 JSON（预签名 URL 不能带 Authorization，否则 OSS 签名冲突） */
    private JsonNode downloadResult(
            WebClient client, String transcriptionUrl, Instant deadline, Duration budget) {
        // 预签名 URL 由 OSS 返回，Content-Type 不一定声明为 application/json，
        // 因此按字符串下载后自行解析，避免 WebClient 因媒体类型不支持而失败
        String body =
                block(
                        client.get()
                                .uri(URI.create(transcriptionUrl))
                                .retrieve()
                                .onStatus(
                                        HttpStatusCode::isError,
                                        clientResponse ->
                                                clientResponse
                                                        .bodyToMono(String.class)
                                                        .defaultIfEmpty("")
                                                        .map(
                                                                errBody ->
                                                                        new BizException(
                                                                                "百炼识别结果下载失败 (HTTP "
                                                                                        + clientResponse
                                                                                                .statusCode()
                                                                                                .value()
                                                                                        + "): "
                                                                                        + describeOssErrorBody(
                                                                                                errBody))))
                                .bodyToMono(String.class),
                        deadline,
                        budget,
                        "下载识别结果");
        if (!StringUtils.hasText(body)) {
            throw new BizException("百炼识别结果为空");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.debug("百炼识别结果不是合法 JSON：长度={}", body.length());
            throw new BizException("百炼识别结果不是合法 JSON");
        }
    }

    // ------------------------------------------------------------------ 结果映射

    /**
     * DashScope 结果 JSON → {@link TranscriptionService.ChannelTranscript}（§2.3）。
     *
     * <ul>
     *   <li>{@code text} = {@code transcripts[0].text}
     *   <li>{@code segments} = {@code sentences[]}（毫秒 → 秒）；{@code speaker_id} 只在开启说话人分离时出现， 本
     *       provider 固定 {@code diarization_enabled=false}，因此 speaker 恒为 null
     *   <li>{@code sentences} 为空但 {@code text} 非空时退化为 {@code [0, duration]} 单段
     *   <li>{@code duration} = {@code properties.original_duration_in_milliseconds / 1000}（四舍五入），
     *       缺失时用最后一句 end
     *   <li>{@code language} = language_hints 第一个值（未配置时为空串）
     * </ul>
     */
    TranscriptionService.ChannelTranscript mapResult(JsonNode root, List<String> languageHints) {
        JsonNode transcripts = root == null ? null : root.path("transcripts");
        String text = "";
        JsonNode sentences = null;
        if (transcripts != null && transcripts.isArray() && !transcripts.isEmpty()) {
            JsonNode first = transcripts.get(0);
            text = first.path("text").asText("");
            sentences = first.path("sentences");
        }

        List<TranscriptSegment> segments = new ArrayList<>();
        double lastEnd = 0;
        if (sentences != null && sentences.isArray()) {
            for (JsonNode sentence : sentences) {
                String sentenceText = sentence.path("text").asText("");
                if (sentenceText.isBlank()) {
                    continue;
                }
                long beginMs = sentence.path("begin_time").asLong(0);
                long endMs = sentence.path("end_time").asLong(beginMs);
                double start = beginMs / 1000.0;
                double end = endMs / 1000.0;
                segments.add(TranscriptSegment.of(null, null, start, end, sentenceText));
                lastEnd = Math.max(lastEnd, end);
            }
        }

        long durationMs =
                root == null
                        ? -1
                        : root.path("properties")
                                .path("original_duration_in_milliseconds")
                                .asLong(-1);
        int durationSeconds =
                durationMs > 0 ? (int) Math.round(durationMs / 1000.0) : (int) Math.round(lastEnd);
        if (segments.isEmpty() && !text.isBlank()) {
            segments.add(TranscriptSegment.of(null, null, 0, durationSeconds, text));
        }

        String language =
                languageHints == null || languageHints.isEmpty() ? "" : languageHints.get(0);
        return new TranscriptionService.ChannelTranscript(
                text, language, durationSeconds, segments);
    }

    // ------------------------------------------------------------------ 错误/工具

    /** 从任务/查询/提交响应中提取 DashScope 的 code + message + request_id。 绝不拼接原始响应体（含临时凭证），找不到错误详情时只回固定文案。 */
    String describeError(JsonNode node) {
        if (node == null) {
            return "无错误详情";
        }
        String code = node.path("code").asText("");
        String message = node.path("message").asText("");
        String requestId = node.path("request_id").asText("");
        JsonNode output = node.path("output");
        if (!StringUtils.hasText(code)) {
            JsonNode result = output.path("results").path(0);
            code = result.path("code").asText("");
            message = result.path("message").asText("");
            if (!StringUtils.hasText(requestId)) {
                requestId = result.path("request_id").asText("");
            }
        }
        if (!StringUtils.hasText(code)) {
            code = output.path("code").asText("");
        }
        if (!StringUtils.hasText(message)) {
            message = output.path("message").asText("");
        }
        if (!StringUtils.hasText(requestId)) {
            requestId = output.path("request_id").asText("");
        }
        if (!StringUtils.hasText(code) && !StringUtils.hasText(message)) {
            return "无错误详情";
        }
        StringBuilder detail = new StringBuilder();
        if (StringUtils.hasText(code)) {
            detail.append(code).append(": ");
        }
        detail.append(message);
        if (StringUtils.hasText(requestId)) {
            detail.append("（request_id=").append(requestId).append("）");
        }
        String safe = detail.toString().trim();
        log.debug("百炼错误详情（已脱敏，仅 code/message/request_id）：{}", safe);
        return safe;
    }

    /** HTTP 错误体（DashScope 原生 JSON 错误）：只回 code/message/request_id，不拼接原始响应体 */
    String describeErrorBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "响应为空";
        }
        try {
            return describeError(objectMapper.readTree(body));
        } catch (Exception e) {
            // 原始响应体可能含临时 OSS 凭证：只记录长度，绝不写进异常消息/日志
            log.debug("百炼错误响应不是合法 JSON（正文已省略，避免泄露临时凭证）：长度={}", body.length());
            return "响应无法解析为 JSON";
        }
    }

    /** OSS 上传/下载错误（XML）：只回 Code/Message/RequestId，不拼接原始响应体 */
    String describeOssErrorBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "响应为空";
        }
        Map<String, String> tags = parseXmlTags(body);
        String code = tags.getOrDefault("Code", "");
        String message = tags.getOrDefault("Message", "");
        String requestId = tags.getOrDefault("RequestId", "");
        if (!StringUtils.hasText(code) && !StringUtils.hasText(message)) {
            log.debug("百炼 OSS 错误响应无法解析（正文已省略，避免泄露临时凭证）：长度={}", body.length());
            return "响应无法解析";
        }
        StringBuilder detail = new StringBuilder();
        if (StringUtils.hasText(code)) {
            detail.append(code).append(": ");
        }
        detail.append(message);
        if (StringUtils.hasText(requestId)) {
            detail.append("（RequestId=").append(requestId).append("）");
        }
        String safe = detail.toString().trim();
        log.debug("百炼 OSS 错误详情（已脱敏，仅 Code/Message/RequestId）：{}", safe);
        return safe;
    }

    /** 只解析白名单标签（Code/Message/RequestId），其余标签内容（如 HostId）一律丢弃 */
    private static Map<String, String> parseXmlTags(String xml) {
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher matcher = XML_TAG.matcher(xml);
        while (matcher.find()) {
            String name = matcher.group(1);
            if ("Code".equals(name) || "Message".equals(name) || "RequestId".equals(name)) {
                tags.put(name, matcher.group(2).trim());
            }
        }
        return tags;
    }

    private URI uploadPolicyUri(Config config) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(config.baseUrl()))
                .path(UPLOADS_PATH)
                .queryParam("action", "getPolicy")
                .queryParam("model", config.model())
                .build()
                .encode()
                .toUri();
    }

    private URI transcriptionUri(Config config) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(config.baseUrl()))
                .path(TRANSCRIPTION_PATH)
                .build()
                .encode()
                .toUri();
    }

    private URI taskUri(Config config, String taskId) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(config.baseUrl()))
                .path(TASKS_PATH + taskId)
                .build()
                .encode()
                .toUri();
    }

    /** upload_host 正常带 scheme；缺省时补 https://（防御异常响应） */
    private URI uploadUri(String uploadHost) {
        String host = uploadHost.trim();
        if (!host.contains("://")) {
            host = "https://" + host;
        }
        return URI.create(host);
    }

    private WebClient buildClient(Duration timeout) {
        HttpClient httpClient = HttpClient.create().responseTimeout(timeout);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 长录音结果 JSON 远超 WebClient 默认 256KB 缓冲上限（B1）
                .exchangeStrategies(
                        ExchangeStrategies.builder()
                                .codecs(
                                        codecs ->
                                                codecs.defaultCodecs()
                                                        .maxInMemorySize(MAX_IN_MEMORY_SIZE))
                                .build())
                .build();
    }

    /** 阻塞等待 + 剩余预算控制：整体预算由 {@code asr.timeout-seconds} 统一约束 */
    private <T> T block(Mono<T> mono, Instant deadline, Duration budget, String stage) {
        Duration remaining = remaining(deadline, budget, stage);
        try {
            return mono.block(remaining);
        } catch (IllegalStateException e) {
            if (AsrProvider.errorMessage(e).contains("Timeout")) {
                throw timeout(budget, stage);
            }
            throw e;
        }
    }

    private Duration remaining(Instant deadline, Duration budget, String stage) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw timeout(budget, stage);
        }
        return remaining;
    }

    private BizException timeout(Duration budget, String stage) {
        return new BizException("百炼转写超时（" + budget.toSeconds() + "s）：" + stage);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("百炼转写等待被中断");
        }
    }

    private static String fileNameOf(Path audioFile) {
        Path name = audioFile.getFileName();
        return name == null || name.toString().isBlank() ? "audio.wav" : name.toString();
    }

    private static String joinObjectKey(String uploadDir, String fileName) {
        String dir = uploadDir == null ? "" : uploadDir.trim();
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.isEmpty() ? fileName : dir + "/" + fileName;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    // ------------------------------------------------------------------ 内部结构

    /** 临时上传凭证（字段名与 DashScope 返回一致；<b>含临时密钥，禁止写日志/异常消息</b>） */
    record UploadPolicy(
            String policy,
            String signature,
            String uploadHost,
            String uploadDir,
            String ossAccessKeyId,
            String xOssObjectAcl,
            String xOssForbidOverwrite,
            long maxFileSizeMb) {}
}
