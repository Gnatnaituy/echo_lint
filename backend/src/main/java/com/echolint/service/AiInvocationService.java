package com.echolint.service;

import com.echolint.entity.AiInvocation;
import com.echolint.repository.AiInvocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 调用留痕：把每次语义复筛 / 敏感词挖掘 / embedding / 评测 / 连通性测试的完整请求与原始回复写库。
 *
 * <p>刻意做成「永不抛异常」—— 留痕是观测手段，不能因为它失败而影响稽核主流程； 写库失败只 WARN 并累加计数器（{@link
 * #persistFailureCount()}），便于外部告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInvocationService {

    public static final String STAGE_SCREEN = "SCREEN";
    public static final String STAGE_MINING = "MINING";

    /** 知识库/语料索引时的 embedding 调用（无录音，recordingId 为空） */
    public static final String STAGE_EMBED_INDEX = "EMBED_INDEX";

    /** 复筛检索时的 query embedding 调用（关联到当前录音） */
    public static final String STAGE_EMBED_QUERY = "EMBED_QUERY";

    /** 模型评测调用（ADMIN 显式触发，无录音；计入成本看板与日配额） */
    public static final String STAGE_EVAL = "EVAL";

    /** 连通性测试调用（供应商/embedding 测试，无录音；计入成本看板与日配额） */
    public static final String STAGE_TEST = "TEST";

    /** 留痕/摘要预览最大字符数：评测/测试的大 prompt 只存摘要，避免把 TEXT 列撑爆 */
    static final int SUMMARY_PREVIEW_CHARS = 500;

    private static final ObjectMapper SUMMARY_MAPPER = new ObjectMapper();

    private final AiInvocationRepository repository;

    /** 留痕写库失败次数（进程内累计，便于监控/告警；不阻塞主流程） */
    private final AtomicLong persistFailures = new AtomicLong();

    /** 某条录音的全部 AI 调用记录（按时间正序） */
    public java.util.List<AiInvocation> listByRecording(Long recordingId) {
        return repository.findByRecordingIdOrderByIdAsc(recordingId);
    }

    /** 录音删除时一并清理留痕 */
    public void deleteByRecording(Long recordingId) {
        try {
            repository.deleteAll(repository.findByRecordingIdOrderByIdAsc(recordingId));
        } catch (Exception e) {
            log.warn("清理 AI 留痕失败 recordingId={}: {}", recordingId, e.getMessage());
        }
    }

    /** 记录一次成功的调用（含 token 用量 / 成本快照，Batch 3a）。 */
    public void recordSuccess(
            Long recordingId,
            String stage,
            ScreenModelService.ActiveModel model,
            String requestJson,
            String responseJson,
            long latencyMs,
            AiUsage usage) {
        save(recordingId, stage, model, requestJson, responseJson, null, latencyMs, false, usage);
    }

    /** 记录一次失败的调用（含密钥缺失、网络错误、返回无法解析等）。 此时 requestJson 仍会落库 —— 排查「为什么没调成」时，看喂了什么和看报错同样重要。 */
    public void recordFailure(
            Long recordingId,
            String stage,
            ScreenModelService.ActiveModel model,
            String requestJson,
            String errorMessage,
            long latencyMs) {
        save(recordingId, stage, model, requestJson, null, errorMessage, latencyMs, true, null);
    }

    /**
     * 记录一次失败但**已被供应商计费**的调用（如模型已返回 200 但输出无法解析/被截断）。 这类调用的 token 与成本必须落账，否则成本看板低估、日 token 配额被绕过。
     */
    public void recordFailure(
            Long recordingId,
            String stage,
            ScreenModelService.ActiveModel model,
            String requestJson,
            String errorMessage,
            long latencyMs,
            AiUsage usage) {
        save(recordingId, stage, model, requestJson, null, errorMessage, latencyMs, true, usage);
    }

    /** 留痕写库失败累计次数（进程内；0 表示正常） */
    public long persistFailureCount() {
        return persistFailures.get();
    }

    /**
     * 生成大请求体的摘要（评测/连通性测试场景）：只保留字符数、SHA-256 与预览， 避免把数万字符的 prompt 写进 {@code
     * ai_invocations.request_json}。
     */
    public static String summarizeRequest(String requestJson) {
        if (requestJson == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chars", requestJson.length());
        summary.put("sha256", sha256(requestJson));
        summary.put(
                "preview",
                requestJson.length() <= SUMMARY_PREVIEW_CHARS
                        ? requestJson
                        : requestJson.substring(0, SUMMARY_PREVIEW_CHARS));
        try {
            return SUMMARY_MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            return "{\"chars\":" + requestJson.length() + "}";
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ------------------------------------------------------------------
    // embedding 调用留痕（RAG，Batch 6）：没有 ScreenModelService.ActiveModel，
    // 直接记录供应商/模型/端点，成本看板与配额统计沿用 ai_invocations 聚合。
    // ------------------------------------------------------------------

    /** 记录一次成功的 embedding 调用 */
    public void recordEmbeddingSuccess(
            Long recordingId,
            String stage,
            String providerId,
            String providerLabel,
            String model,
            String endpoint,
            String requestJson,
            String responseJson,
            long latencyMs,
            AiUsage usage) {
        save(
                recordingId,
                stage,
                providerId,
                providerLabel,
                model,
                endpoint,
                requestJson,
                responseJson,
                null,
                latencyMs,
                false,
                usage);
    }

    /** 记录一次失败的 embedding 调用（请求体仍落库，便于排查） */
    public void recordEmbeddingFailure(
            Long recordingId,
            String stage,
            String providerId,
            String providerLabel,
            String model,
            String endpoint,
            String requestJson,
            String errorMessage,
            long latencyMs) {
        save(
                recordingId,
                stage,
                providerId,
                providerLabel,
                model,
                endpoint,
                requestJson,
                null,
                errorMessage,
                latencyMs,
                true,
                null);
    }

    /** 记录一次失败但已产生 token 消耗的 embedding 调用（如响应维度不匹配，供应商已计费） */
    public void recordEmbeddingFailure(
            Long recordingId,
            String stage,
            String providerId,
            String providerLabel,
            String model,
            String endpoint,
            String requestJson,
            String errorMessage,
            long latencyMs,
            AiUsage usage) {
        save(
                recordingId,
                stage,
                providerId,
                providerLabel,
                model,
                endpoint,
                requestJson,
                null,
                errorMessage,
                latencyMs,
                true,
                usage);
    }

    private void save(
            Long recordingId,
            String stage,
            ScreenModelService.ActiveModel model,
            String requestJson,
            String responseJson,
            String errorMessage,
            long latencyMs,
            boolean degraded,
            AiUsage usage) {
        if (model == null) {
            save(
                    recordingId,
                    stage,
                    null,
                    null,
                    null,
                    null,
                    requestJson,
                    responseJson,
                    errorMessage,
                    latencyMs,
                    degraded,
                    usage);
            return;
        }
        save(
                recordingId,
                stage,
                model.providerId(),
                model.providerLabel(),
                model.model(),
                ScreenModelService.endpointOf(model),
                requestJson,
                responseJson,
                errorMessage,
                latencyMs,
                degraded,
                usage);
    }

    private void save(
            Long recordingId,
            String stage,
            String providerId,
            String providerLabel,
            String model,
            String endpoint,
            String requestJson,
            String responseJson,
            String errorMessage,
            long latencyMs,
            boolean degraded,
            AiUsage usage) {
        try {
            AiInvocation inv = new AiInvocation();
            inv.setRecordingId(recordingId);
            inv.setStage(stage);
            inv.setProviderId(providerId);
            inv.setProviderLabel(providerLabel);
            inv.setModel(model);
            inv.setEndpoint(endpoint);
            inv.setDegraded(degraded);
            inv.setRequestJson(requestJson);
            inv.setResponseJson(responseJson);
            inv.setErrorMessage(errorMessage);
            inv.setSuccess(errorMessage == null);
            inv.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            inv.setRequestChars(requestJson == null ? 0 : requestJson.length());
            inv.setResponseChars(responseJson == null ? 0 : responseJson.length());
            if (usage != null) {
                inv.setInputTokens(usage.inputTokens());
                inv.setOutputTokens(usage.outputTokens());
                inv.setCostMicros(usage.costMicros());
            }
            repository.save(inv);
        } catch (Exception e) {
            persistFailures.incrementAndGet();
            log.warn(
                    "AI 调用留痕写入失败（不影响主流程，累计 {} 次）recordingId={} stage={}: {}",
                    persistFailures.get(),
                    recordingId,
                    stage,
                    e.getMessage());
        }
    }
}
