package com.echolint.service.rag;

import com.echolint.config.RagProperties;
import com.echolint.service.AiInvocationService;
import com.echolint.service.AiQuotaService;
import com.echolint.service.AiUsage;
import com.echolint.service.ProviderRegistry;
import com.echolint.service.ProviderRegistry.ResolvedProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容 /embeddings 协议的 embedding 客户端（Batch 6）。
 *
 * <p>复用 {@code ai_providers} 中的供应商配置（base-url + AES-GCM 密钥 + 输入单价），
 * 只额外指定 embedding 模型/路径/维度；因此密钥轮换、供应商停用与现有 AI 治理一致。</p>
 *
 * <p>行为约定：</p>
 * <ul>
 *   <li>429/5xx 重试 2 次（300ms、900ms 退避），其余错误立即失败；</li>
 *   <li>调用前过每日 token 配额；每次调用写入 ai_invocations（stage=EMBED_INDEX/EMBED_QUERY），
 *       请求/响应只存摘要，不存整段文本与全部向量，避免留痕表膨胀；</li>
 *   <li>返回向量维度必须等于配置维度，否则视为配置/模型不匹配。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEmbeddingClient {

    private static final int MAX_RETRIES = 2;
    private static final long FIRST_RETRY_DELAY_MS = 300;
    private static final long SECOND_RETRY_DELAY_MS = 900;
    private static final int PREVIEW_CHARS = 120;

    private final ProviderRegistry providers;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final AiInvocationService invocations;
    private final AiQuotaService quotaService;

    /** embedding 配置信息（系统设置页只读展示） */
    public record EmbeddingInfo(String providerId, String providerLabel, String model, String path,
                                int dimensions, long timeoutSeconds, boolean apiKeyConfigured) {
    }

    /** 连通性测试结果 */
    public record TestResult(boolean ok, String providerId, String providerLabel, String model,
                             int dimensions, long latencyMs, String message) {
    }

    /** 一次 embedding 批次的返回 */
    public record EmbeddingBatch(List<float[]> vectors, int dimensions, AiUsage usage, long latencyMs) {
    }

    /** 是否具备调用条件（供应商存在、启用、密钥已配置）；不发起网络请求 */
    public boolean available() {
        try {
            ResolvedProvider provider = requireProvider();
            return provider.enabled() && provider.apiKeyConfigured()
                    && !provider.baseUrl().isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 配置视图（不发起请求） */
    public EmbeddingInfo info() {
        ResolvedProvider provider = resolveProviderOrNull();
        return new EmbeddingInfo(
                provider == null ? properties.effectiveEmbeddingProviderId() : provider.providerKey(),
                provider == null ? properties.effectiveEmbeddingProviderId() : provider.displayLabel(),
                properties.effectiveEmbeddingModel(),
                properties.effectiveEmbeddingPath(),
                properties.effectiveDimensions(),
                properties.effectiveEmbeddingTimeoutSeconds(),
                provider != null && provider.apiKeyConfigured());
    }

    /**
     * 批量向量化；返回顺序与入参一致。
     *
     * @param recordingId 关联录音（查询向量化时有值，索引时为空）
     * @param stage       EMBED_INDEX / EMBED_QUERY
     */
    public List<float[]> embed(List<String> texts, Long recordingId, String stage) {
        List<String> inputs = normalizeInputs(texts);
        List<float[]> out = new ArrayList<>(inputs.size());
        int batchSize = properties.effectiveBatchSize();
        for (int from = 0; from < inputs.size(); from += batchSize) {
            int to = Math.min(inputs.size(), from + batchSize);
            out.addAll(embedBatch(inputs.subList(from, to), recordingId, stage));
        }
        return out;
    }

    /** 单条查询向量化（复筛检索用） */
    public float[] embedQuery(String text, Long recordingId) {
        List<float[]> vectors = embed(List.of(text == null ? "" : text), recordingId,
                AiInvocationService.STAGE_EMBED_QUERY);
        return vectors.isEmpty() ? new float[properties.effectiveDimensions()] : vectors.get(0);
    }

    /** 连通性测试：真实调用一次 embedding 并校验维度 */
    public TestResult test() {
        long start = System.currentTimeMillis();
        ResolvedProvider provider;
        try {
            provider = requireProvider();
        } catch (RuntimeException e) {
            return new TestResult(false, properties.effectiveEmbeddingProviderId(),
                    properties.effectiveEmbeddingProviderId(), properties.effectiveEmbeddingModel(),
                    properties.effectiveDimensions(), 0, e.getMessage());
        }
        try {
            EmbeddingBatch batch = callWithRetry(provider, List.of("EchoLint RAG embedding connectivity test"));
            return new TestResult(true, provider.providerKey(), provider.displayLabel(),
                    properties.effectiveEmbeddingModel(), batch.dimensions(),
                    System.currentTimeMillis() - start,
                    "embedding 调用成功，维度 " + batch.dimensions() + "，输入 tokens "
                            + (batch.usage() == null || batch.usage().inputTokens() == null
                            ? "未知" : batch.usage().inputTokens()));
        } catch (RuntimeException e) {
            return new TestResult(false, provider.providerKey(), provider.displayLabel(),
                    properties.effectiveEmbeddingModel(), properties.effectiveDimensions(),
                    System.currentTimeMillis() - start, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    private List<String> normalizeInputs(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new RagEmbeddingException("embedding 输入为空");
        }
        List<String> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new RagEmbeddingException("embedding 输入包含空文本");
            }
            out.add(text.length() > 20_000 ? text.substring(0, 20_000) : text);
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts, Long recordingId, String stage) {
        ResolvedProvider provider = requireProvider();
        quotaService.checkDailyTokensOnly();
        String endpoint = endpoint(provider);
        String requestJson = summaryRequest(provider, texts);
        long start = System.currentTimeMillis();
        try {
            EmbeddingBatch batch = callWithRetry(provider, texts);
            invocations.recordEmbeddingSuccess(recordingId, stage, provider.providerKey(),
                    provider.displayLabel(), properties.effectiveEmbeddingModel(), endpoint,
                    requestJson, summaryResponse(batch), System.currentTimeMillis() - start,
                    batch.usage());
            return batch.vectors();
        } catch (RuntimeException e) {
            invocations.recordEmbeddingFailure(recordingId, stage, provider.providerKey(),
                    provider.displayLabel(), properties.effectiveEmbeddingModel(), endpoint,
                    requestJson, truncate(e.getMessage(), 500), System.currentTimeMillis() - start);
            if (e instanceof RagEmbeddingException) {
                throw e;
            }
            throw new RagEmbeddingException("embedding 调用失败 [" + provider.displayLabel() + "]："
                    + e.getMessage(), e);
        }
    }

    private EmbeddingBatch callWithRetry(ResolvedProvider provider, List<String> texts) {
        RagHttpException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doCall(provider, texts);
            } catch (RagHttpException e) {
                last = e;
                if (!e.retryable() || attempt >= MAX_RETRIES) {
                    throw e;
                }
                long delay = attempt == 0 ? FIRST_RETRY_DELAY_MS : SECOND_RETRY_DELAY_MS;
                log.warn("embedding 调用返回 HTTP {}，{}ms 后重试（{}/{}）",
                        e.status(), delay, attempt + 1, MAX_RETRIES);
                sleep(delay);
            }
        }
        throw last == null ? new RagEmbeddingException("embedding 调用失败") : last;
    }

    private EmbeddingBatch doCall(ResolvedProvider provider, List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.effectiveEmbeddingModel());
        body.put("input", texts);
        if (properties.sendEmbeddingDimensions()) {
            body.put("dimensions", properties.effectiveDimensions());
        }
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RagEmbeddingException("embedding 请求体序列化失败：" + e.getMessage(), e);
        }
        long start = System.currentTimeMillis();
        JsonNode response;
        try {
            response = buildClient(provider).post()
                    .uri(properties.effectiveEmbeddingPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(bodyJson)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, cr -> cr.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(errorBody -> new RagHttpException(cr.statusCode().value(),
                                    "HTTP " + cr.statusCode().value() + ": "
                                            + truncate(errorBody, 300))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(properties.effectiveEmbeddingTimeoutSeconds()));
        } catch (RagHttpException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RagEmbeddingException("embedding 请求失败（超时/网络）：" + e.getMessage(), e);
        }
        if (response == null) {
            throw new RagEmbeddingException("embedding 返回为空");
        }
        return parseResponse(response, System.currentTimeMillis() - start, provider);
    }

    private EmbeddingBatch parseResponse(JsonNode response, long latencyMs, ResolvedProvider provider) {
        JsonNode data = response.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new RagEmbeddingException("embedding 响应缺少 data 数组");
        }
        int dimensions = properties.effectiveDimensions();
        List<IndexedVector> indexed = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                throw new RagEmbeddingException("embedding 响应缺少向量");
            }
            if (embedding.size() != dimensions) {
                throw new RagEmbeddingException("embedding 维度不匹配：配置 " + dimensions
                        + "，实际 " + embedding.size() + "（请检查 app.rag.embedding.dimensions 与模型）");
            }
            float[] values = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                values[i] = (float) embedding.get(i).asDouble();
            }
            int index = item.hasNonNull("index") ? item.get("index").asInt(indexed.size()) : indexed.size();
            indexed.add(new IndexedVector(index, values));
        }
        indexed.sort(Comparator.comparingInt(IndexedVector::index));
        List<float[]> vectors = indexed.stream().map(IndexedVector::vector).toList();
        AiUsage raw = AiUsage.extract(response);
        AiUsage usage = new AiUsage(raw.inputTokens(), 0,
                AiUsage.costMicros(raw.inputTokens(), 0, provider.inputPricePer1k(), BigDecimal.ZERO));
        return new EmbeddingBatch(vectors, dimensions, usage, latencyMs);
    }

    private WebClient buildClient(ResolvedProvider provider) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(properties.effectiveEmbeddingTimeoutSeconds()));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(trimTrailingSlash(provider.baseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (provider.apiKeyConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private ResolvedProvider requireProvider() {
        ResolvedProvider provider = resolveProviderOrNull();
        if (provider == null) {
            throw new RagEmbeddingException("未找到 embedding 供应商："
                    + properties.effectiveEmbeddingProviderId()
                    + "（请在 application.yml 的 app.rag.embedding.provider-id 配置，"
                    + "并确认该供应商已在「AI 供应商」中存在）");
        }
        if (!provider.enabled()) {
            throw new RagEmbeddingException("embedding 供应商已停用：" + provider.displayLabel());
        }
        if (!provider.apiKeyConfigured()) {
            throw new RagEmbeddingException("embedding 供应商未配置 API Key：" + provider.displayLabel()
                    + "（" + provider.apiKeyHint() + "）");
        }
        if (provider.baseUrl() == null || provider.baseUrl().isBlank()) {
            throw new RagEmbeddingException("embedding 供应商缺少 base-url：" + provider.displayLabel());
        }
        return provider;
    }

    private ResolvedProvider resolveProviderOrNull() {
        return providers.find(properties.effectiveEmbeddingProviderId()).orElse(null);
    }

    private String endpoint(ResolvedProvider provider) {
        String base = trimTrailingSlash(provider.baseUrl());
        String path = properties.effectiveEmbeddingPath();
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String summaryRequest(ResolvedProvider provider, List<String> texts) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", properties.effectiveEmbeddingModel());
        summary.put("endpoint", endpoint(provider));
        if (properties.sendEmbeddingDimensions()) {
            summary.put("dimensions", properties.effectiveDimensions());
        }
        summary.put("inputCount", texts.size());
        summary.put("inputChars", texts.stream().mapToInt(String::length).sum());
        summary.put("preview", truncate(texts.get(0), PREVIEW_CHARS));
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return null;
        }
    }

    private String summaryResponse(EmbeddingBatch batch) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dimensions", batch.dimensions());
        summary.put("count", batch.vectors().size());
        if (batch.usage() != null && batch.usage().inputTokens() != null) {
            summary.put("inputTokens", batch.usage().inputTokens());
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagEmbeddingException("embedding 重试被中断", e);
        }
    }

    private record IndexedVector(int index, float[] vector) {
    }

    /** 可重试的 HTTP 错误（429/5xx） */
    private static final class RagHttpException extends RagEmbeddingException {
        private final int status;

        RagHttpException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }

        boolean retryable() {
            return status == 429 || status >= 500;
        }
    }
}
