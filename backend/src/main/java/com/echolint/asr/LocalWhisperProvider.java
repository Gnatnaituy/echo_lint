package com.echolint.asr;

import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import com.echolint.service.TranscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

/**
 * 本地 whisper.cpp 转写（OpenAI 兼容接口，音频不出本机）。
 *
 * <p>从旧 {@code TranscriptionService} 原样迁入的调用逻辑：multipart 上传 {@code file}、 {@code model}、{@code
 * response_format=verbose_json}，解析 {@code text / language / duration / segments}。 base-url /
 * api-key / model 每次按当前配置构造，不再使用固定 baseUrl 的单例 WebClient。
 *
 * <p>长录音的 verbose_json（segments 很多）会超过 WebClient 默认 256KB 响应缓冲， 因此显式放宽 {@code maxInMemorySize}（与云端
 * provider 一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalWhisperProvider implements AsrProvider {

    private static final String DEFAULT_AUDIO_PATH = "/v1/audio/transcriptions";

    /** WebClient 默认 256KB，长录音 verbose_json 会超限（DataBufferLimitException） */
    static final int MAX_IN_MEMORY_SIZE = 32 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Override
    public AsrProviderType type() {
        return AsrProviderType.LOCAL;
    }

    @Override
    public TranscriptionService.ChannelTranscript transcribe(Config config, Path audioFile) {
        if (!config.ready()) {
            throw new BizException("本地 whisper.cpp 未就绪：" + AsrProvider.missingDescription(config));
        }
        String audioPath =
                config.audioPath() == null || config.audioPath().isBlank()
                        ? DEFAULT_AUDIO_PATH
                        : config.audioPath().trim();
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(audioFile.toFile()));
        parts.add("model", config.model());
        parts.add("response_format", "verbose_json");

        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
        JsonNode resp;
        try {
            resp =
                    buildClient(config, timeout)
                            .post()
                            .uri(audioPath)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(BodyInserters.fromMultipartData(parts))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::isError,
                                    clientResponse ->
                                            clientResponse
                                                    .bodyToMono(String.class)
                                                    .map(
                                                            errBody ->
                                                                    new BizException(
                                                                            "Whisper 转写失败 (HTTP "
                                                                                    + clientResponse
                                                                                            .statusCode()
                                                                                            .value()
                                                                                    + "): "
                                                                                    + extractErrorMessage(
                                                                                            errBody))))
                            .bodyToMono(JsonNode.class)
                            .block(timeout);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("本地 whisper.cpp 调用异常: " + AsrProvider.errorMessage(e));
        }

        if (resp == null) {
            throw new BizException("本地 whisper.cpp 转写超时或返回为空");
        }
        String text = resp.path("text").asText("");
        String language = resp.path("language").asText("");
        int duration = (int) Math.round(resp.path("duration").asDouble(0));
        List<TranscriptSegment> segments = parseSegments(resp.path("segments"));
        log.info(
                "本地 whisper.cpp 转写完成：{} 秒，语言={}，分段 {} 段，文本长度={}",
                duration,
                language,
                segments.size(),
                text.length());
        return new TranscriptionService.ChannelTranscript(text, language, duration, segments);
    }

    @Override
    public ConnectResult test(Config config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            return new ConnectResult(false, "未配置本地 whisper.cpp 服务地址");
        }
        Duration timeout = Duration.ofSeconds(Math.min(config.timeoutSeconds(), 30));
        try {
            // whisper.cpp 服务根路径没有业务接口，只要 HTTP 有响应即视为可达（200/404 都算）
            buildClient(config, timeout)
                    .get()
                    .uri("/")
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout);
            return new ConnectResult(true, "连通正常（本地 whisper.cpp 服务可达）");
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                // 服务可达但鉴权失败：不能报「连通正常」，否则管理员会误以为 Key 配置正确
                return new ConnectResult(false, "服务可达但鉴权失败（HTTP " + status + "）：请检查 API Key");
            }
            return new ConnectResult(true, "服务可达（本地 whisper.cpp 返回 HTTP " + status + "）");
        } catch (Exception e) {
            return new ConnectResult(false, "连接失败: " + AsrProvider.errorMessage(e));
        }
    }

    /** 每次按当前配置构造 WebClient（base-url / api-key 改了立即生效，无需重建单例 bean）。 */
    private WebClient buildClient(Config config, Duration timeout) {
        HttpClient httpClient = HttpClient.create().responseTimeout(timeout);
        WebClient.Builder builder =
                WebClient.builder()
                        .baseUrl(trimTrailingSlash(config.baseUrl()))
                        .clientConnector(new ReactorClientHttpConnector(httpClient))
                        // 长录音 verbose_json 远超 WebClient 默认 256KB 缓冲上限（B1）
                        .exchangeStrategies(
                                ExchangeStrategies.builder()
                                        .codecs(
                                                codecs ->
                                                        codecs.defaultCodecs()
                                                                .maxInMemorySize(
                                                                        MAX_IN_MEMORY_SIZE))
                                        .build())
                        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    private List<TranscriptSegment> parseSegments(JsonNode segmentsNode) {
        List<TranscriptSegment> segments = new ArrayList<>();
        if (segmentsNode == null || !segmentsNode.isArray()) {
            return segments;
        }
        for (JsonNode node : segmentsNode) {
            String segmentText = node.path("text").asText("");
            if (segmentText.isBlank()) {
                continue;
            }
            double start = node.path("start").asDouble(0);
            double end = node.path("end").asDouble(start);
            segments.add(TranscriptSegment.of(null, null, start, end, segmentText));
        }
        return segments;
    }

    private String extractErrorMessage(String errorBody) {
        try {
            JsonNode node = objectMapper.readTree(errorBody);
            String msg = node.path("error").path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
            // 兼容非 JSON 错误体：落到下面的截断原文
        }
        return errorBody != null && errorBody.length() > 300
                ? errorBody.substring(0, 300)
                : String.valueOf(errorBody);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
