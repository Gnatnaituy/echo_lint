package com.echolint.service;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.entity.AiInvocation;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import com.echolint.repository.AiInvocationRepository.SummaryProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 「AI 对话记录」查询服务（Batch 6，仅 ADMIN）。
 *
 * <p>列表只返回摘要，避免把整段提示词塞进列表响应；详情接口才返回完整 requestJson/responseJson。 数据面包含所有 AI 调用（语义复筛 / 词挖掘 /
 * embedding / 评测 / 连通性测试），因此本页面同时承担 AI 调用审计入口。
 *
 * <p>列表查询使用接口投影（{@link SummaryProjection}）只取摘要列，不会把 request_json / response_json 两个 TEXT
 * 大字段从数据库读出来再丢弃（Batch 6 收尾优化）。
 */
@Service
@RequiredArgsConstructor
public class AiInvocationQueryService {

    private static final int ERROR_PREVIEW_CHARS = 300;

    private final AiInvocationRepository repository;

    public Page<AiInvocationSummary> query(AiInvocationFilter filter, Pageable pageable) {
        String stage =
                filter != null && StringUtils.hasText(filter.stage())
                        ? filter.stage().trim().toUpperCase(Locale.ROOT)
                        : null;
        String providerId =
                filter != null && StringUtils.hasText(filter.providerId())
                        ? filter.providerId().trim().toLowerCase(Locale.ROOT)
                        : null;
        String modelPattern =
                filter != null && StringUtils.hasText(filter.model())
                        ? "%" + filter.model().trim().toLowerCase(Locale.ROOT) + "%"
                        : null;
        Boolean success = filter == null ? null : filter.success();
        Long recordingId = filter == null ? null : filter.recordingId();
        LocalDateTime fromTime =
                filter == null || filter.from() == null ? null : filter.from().atStartOfDay();
        LocalDateTime toTime =
                filter == null || filter.to() == null
                        ? null
                        : filter.to().plusDays(1).atStartOfDay();
        return repository
                .findSummaries(
                        stage,
                        providerId,
                        modelPattern,
                        success,
                        recordingId,
                        fromTime,
                        toTime,
                        pageable)
                .map(AiInvocationQueryService::toSummary);
    }

    private static AiInvocationSummary toSummary(SummaryProjection p) {
        return new AiInvocationSummary(
                p.getId(),
                p.getRecordingId(),
                p.getStage(),
                p.getProviderId(),
                p.getProviderLabel(),
                p.getModel(),
                p.getEndpoint(),
                p.getSuccess(),
                p.getDegraded(),
                p.getLatencyMs(),
                p.getRequestChars(),
                p.getResponseChars(),
                p.getInputTokens(),
                p.getOutputTokens(),
                p.getCostMicros(),
                truncate(p.getErrorMessage(), ERROR_PREVIEW_CHARS),
                p.getCreatedAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public AiInvocationDetail detail(Long id) {
        AiInvocation entity =
                repository
                        .findById(id)
                        .orElseThrow(
                                () -> new BizException("AI 调用记录不存在: " + id, HttpStatus.NOT_FOUND));
        return AiInvocationDetail.from(entity);
    }

    public AiInvocationFacets facets() {
        List<AiInvocationFacets.ProviderOption> providers =
                repository.findDistinctProviders().stream()
                        .map(
                                row ->
                                        new AiInvocationFacets.ProviderOption(
                                                row[0] == null ? null : String.valueOf(row[0]),
                                                row[1] == null ? null : String.valueOf(row[1])))
                        .filter(provider -> StringUtils.hasText(provider.id()))
                        .toList();
        return new AiInvocationFacets(
                repository.findDistinctStages(), providers, repository.findDistinctModels());
    }
}
