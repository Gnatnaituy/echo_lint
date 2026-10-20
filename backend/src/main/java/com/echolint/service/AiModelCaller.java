package com.echolint.service;

import com.echolint.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 兼容 chat completions 调用器（Batch 3a）：
 * 对 HTTP 429 / 5xx 做最多 2 次重试（500ms、1500ms 退避），其它错误立即失败。
 *
 * <p>最终失败抛 {@link BizException}，由流水线降级人工复核。网络异常/超时保持原有行为
 * （由 WebClient 的 responseTimeout 控制，异常直接上抛）。</p>
 */
@Slf4j
@Service
public class AiModelCaller {

    /** 第 1、2 次重试前的退避（毫秒） */
    static final long FIRST_RETRY_DELAY_MS = 500;
    static final long SECOND_RETRY_DELAY_MS = 1500;
    static final int MAX_RETRIES = 2;

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final ObjectMapper objectMapper;
    private final long firstRetryDelayMs;
    private final long secondRetryDelayMs;

    @Autowired
    public AiModelCaller(ObjectMapper objectMapper) {
        this(objectMapper, FIRST_RETRY_DELAY_MS, SECOND_RETRY_DELAY_MS);
    }

    /** 测试用：缩短退避，避免单测等待 */
    AiModelCaller(ObjectMapper objectMapper, long firstRetryDelayMs, long secondRetryDelayMs) {
        this.objectMapper = objectMapper;
        this.firstRetryDelayMs = Math.max(0, firstRetryDelayMs);
        this.secondRetryDelayMs = Math.max(0, secondRetryDelayMs);
    }

    /**
     * 发起一次 chat completions 调用（含重试），超时取模型默认配置。
     *
     * @param action 调用场景名（语义复筛 / 词挖掘 / 模型评测），只用于错误信息与日志
     * @return 响应 JSON（非空）
     */
    public JsonNode call(ScreenModelService.ActiveModel model, String requestJson, String action) {
        return call(model, requestJson, action, model.timeoutSeconds());
    }

    /**
     * 发起一次 chat completions 调用（含重试），显式指定单次请求超时（模型评测用于限制单条耗时）。
     *
     * @param timeoutSeconds 单次请求超时秒数；≤0 时回退默认值
     */
    public JsonNode call(ScreenModelService.ActiveModel model, String requestJson, String action,
                         long timeoutSeconds) {
        long effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode response = doCall(model, requestJson, action, effectiveTimeout);
                if (response == null) {
                    throw new BizException(action + "超时或返回为空 [" + model.display() + "]",
                            HttpStatus.INTERNAL_SERVER_ERROR);
                }
                return response;
            } catch (RetryableStatusException e) {
                if (attempt >= MAX_RETRIES) {
                    log.warn("{}调用失败 [{}]（HTTP {}），已重试 {} 次仍失败，放弃并降级", action,
                            model.display(), e.httpStatus, MAX_RETRIES);
                    throw e;
                }
                long delay = attempt == 0 ? firstRetryDelayMs : secondRetryDelayMs;
                log.warn("{}调用返回 HTTP {} [{}]，{}ms 后进行第 {}/{} 次重试", action, e.httpStatus,
                        model.display(), delay, attempt + 1, MAX_RETRIES);
                sleep(delay);
            }
        }
    }

    private JsonNode doCall(ScreenModelService.ActiveModel model, String requestJson, String action,
                            long timeoutSeconds) {
        return model.client().post()
                .uri(model.chatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .onStatus(AiModelCaller::isRetryable, clientResponse ->
                        clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(errorBody ->
                                new RetryableStatusException(
                                        clientResponse.statusCode().value(),
                                        errorMessage(action, model, clientResponse.statusCode().value(), errorBody))))
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(errorBody ->
                                new BizException(
                                        errorMessage(action, model, clientResponse.statusCode().value(), errorBody),
                                        HttpStatus.INTERNAL_SERVER_ERROR)))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    private static boolean isRetryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private String errorMessage(String action, ScreenModelService.ActiveModel model, int status, String errorBody) {
        return action + "调用失败 [" + model.display() + "] (HTTP " + status + "): "
                + ScreenModelService.extractErrorMessage(objectMapper, errorBody);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("AI 调用重试被中断", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** 可重试的 HTTP 状态（429/5xx），保留原始状态码供日志使用 */
    static final class RetryableStatusException extends BizException {

        private final int httpStatus;

        RetryableStatusException(int httpStatus, String message) {
            super(message, HttpStatus.INTERNAL_SERVER_ERROR);
            this.httpStatus = httpStatus;
        }

        int httpStatus() {
            return httpStatus;
        }
    }
}
