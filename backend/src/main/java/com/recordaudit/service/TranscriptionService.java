package com.recordaudit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recordaudit.config.OpenAiProperties;
import com.recordaudit.domain.TranscriptSegment;
import com.recordaudit.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Whisper 语音转写（OpenAI whisper-1, response_format=verbose_json）
 * 单次调用只负责一路音频；双声道由 {@link AudioTranscriptionService} 编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final OpenAiProperties props;
    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;

    /**
     * @param text      Whisper 完整文本
     * @param segments  分段（未带说话人/声道，由上层补齐）
     * @param duration  时长（秒）
     */
    public record ChannelTranscript(String text, String language, int durationSeconds, List<TranscriptSegment> segments) {
    }

    public ChannelTranscript transcribe(Path audioFile) {
        checkApiKey();
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(audioFile.toFile()));
        parts.add("model", props.getWhisperModel());
        parts.add("response_format", "verbose_json");

        JsonNode resp;
        try {
            resp = openAiWebClient.post()
                    .uri(props.getAudioPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class).map(errBody ->
                                    new BizException("Whisper 转写失败 (HTTP " + clientResponse.statusCode().value() + "): "
                                            + extractErrorMessage(errBody))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(props.getRequestTimeoutSeconds()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("Whisper 调用异常: " + e.getMessage());
        }

        if (resp == null) {
            throw new BizException("Whisper 转写超时或返回为空");
        }
        String text = resp.path("text").asText("");
        String language = resp.path("language").asText("");
        int duration = (int) Math.round(resp.path("duration").asDouble(0));
        List<TranscriptSegment> segments = parseSegments(resp.path("segments"));
        log.info("Whisper 转写完成：{} 秒，语言={}，分段 {} 段，文本长度={}",
                duration, language, segments.size(), text.length());
        return new ChannelTranscript(text, language, duration, segments);
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

    private void checkApiKey() {
        if (!props.apiKeyConfigured()) {
            throw new BizException("未配置 OPENAI_API_KEY，请设置环境变量后重启服务");
        }
    }

    private String extractErrorMessage(String errorBody) {
        try {
            JsonNode node = objectMapper.readTree(errorBody);
            String msg = node.path("error").path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return errorBody != null && errorBody.length() > 300 ? errorBody.substring(0, 300) : String.valueOf(errorBody);
    }
}
