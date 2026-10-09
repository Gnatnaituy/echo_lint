package com.echolint.service;

import com.echolint.entity.AiInvocation;
import com.echolint.repository.AiInvocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 调用留痕：把每次语义复筛 / 敏感词挖掘的完整请求与原始回复写库。
 *
 * 刻意做成「永不抛异常」—— 留痕是观测手段，不能因为它失败而影响稽核主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInvocationService {

    public static final String STAGE_SCREEN = "SCREEN";
    public static final String STAGE_MINING = "MINING";

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
     * 记录一次成功的调用
     */
    public void recordSuccess(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                              String requestJson, String responseJson, long latencyMs) {
        save(recordingId, stage, model, requestJson, responseJson, null, latencyMs, false);
    }

    /**
     * 记录一次失败的调用（含密钥缺失、网络错误、返回无法解析等）。
     * 此时 requestJson 仍会落库 —— 排查「为什么没调成」时，看喂了什么和看报错同样重要。
     */
    public void recordFailure(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                              String requestJson, String errorMessage, long latencyMs) {
        save(recordingId, stage, model, requestJson, null, errorMessage, latencyMs, true);
    }

    private void save(Long recordingId, String stage, ScreenModelService.ActiveModel model,
                      String requestJson, String responseJson, String errorMessage,
                      long latencyMs, boolean degraded) {
        try {
            AiInvocation inv = new AiInvocation();
            inv.setRecordingId(recordingId);
            inv.setStage(stage);
            if (model != null) {
                inv.setProviderId(model.providerId());
                inv.setProviderLabel(model.providerLabel());
                inv.setModel(model.model());
                inv.setEndpoint(ScreenModelService.endpointOf(model));
                inv.setDegraded(degraded);
            }
            inv.setRequestJson(requestJson);
            inv.setResponseJson(responseJson);
            inv.setErrorMessage(errorMessage);
            inv.setSuccess(errorMessage == null);
            inv.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            inv.setRequestChars(requestJson == null ? 0 : requestJson.length());
            inv.setResponseChars(responseJson == null ? 0 : responseJson.length());
            repository.save(inv);
        } catch (Exception e) {
            log.warn("AI 调用留痕写入失败（不影响主流程）recordingId={} stage={}: {}",
                    recordingId, stage, e.getMessage());
        }
    }
}
