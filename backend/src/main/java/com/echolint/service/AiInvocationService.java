package com.echolint.service;

import com.echolint.entity.AiInvocation;
import com.echolint.repository.AiInvocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 调用留痕：把每次语义复筛 / 敏感词挖掘 / embedding 的完整请求与原始回复写库。
 *
 * <p>刻意做成「永不抛异常」—— 留痕是观测手段，不能因为它失败而影响稽核主流程。</p>
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

    private final AiInvocationRepository repository;

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

    /**
     * 记录一次成功的调用（含 token 用量 / 成本快照，Batch 3a）。
     */
    public void recordSuccess(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                              String requestJson, String responseJson, long latencyMs, AiUsage usage) {
        save(recordingId, stage, model, requestJson, responseJson, null, latencyMs, false, usage);
    }

    /**
     * 记录一次失败的调用（含密钥缺失、网络错误、返回无法解析等）。
     * 此时 requestJson 仍会落库 —— 排查「为什么没调成」时，看喂了什么和看报错同样重要。
     */
    public void recordFailure(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                              String requestJson, String errorMessage, long latencyMs) {
        save(recordingId, stage, model, requestJson, null, errorMessage, latencyMs, true, null);
    }

    // ------------------------------------------------------------------
    // embedding 调用留痕（RAG，Batch 6）：没有 ScreenModelService.ActiveModel，
    // 直接记录供应商/模型/端点，成本看板与配额统计沿用 ai_invocations 聚合。
    // ------------------------------------------------------------------

    /** 记录一次成功的 embedding 调用 */
    public void recordEmbeddingSuccess(Long recordingId, String stage, String providerId,
                                       String providerLabel, String model, String endpoint,
                                       String requestJson, String responseJson, long latencyMs,
                                       AiUsage usage) {
        save(recordingId, stage, providerId, providerLabel, model, endpoint,
                requestJson, responseJson, null, latencyMs, false, usage);
    }

    /** 记录一次失败的 embedding 调用（请求体仍落库，便于排查） */
    public void recordEmbeddingFailure(Long recordingId, String stage, String providerId,
                                       String providerLabel, String model, String endpoint,
                                       String requestJson, String errorMessage, long latencyMs) {
        save(recordingId, stage, providerId, providerLabel, model, endpoint,
                requestJson, null, errorMessage, latencyMs, true, null);
    }

    private void save(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                      String requestJson, String responseJson, String errorMessage,
                      long latencyMs, boolean degraded, AiUsage usage) {
        if (model == null) {
            save(recordingId, stage, null, null, null, null,
                    requestJson, responseJson, errorMessage, latencyMs, degraded, usage);
            return;
        }
        save(recordingId, stage, model.providerId(), model.providerLabel(), model.model(),
                ScreenModelService.endpointOf(model), requestJson, responseJson, errorMessage,
                latencyMs, degraded, usage);
    }

    private void save(Long recordingId, String stage, String providerId, String providerLabel,
                      String model, String endpoint, String requestJson, String responseJson,
                      String errorMessage, long latencyMs, boolean degraded, AiUsage usage) {
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
            log.warn("AI 调用留痕写入失败（不影响主流程）recordingId={} stage={}: {}",
                    recordingId, stage, e.getMessage());
        }
    }
}
