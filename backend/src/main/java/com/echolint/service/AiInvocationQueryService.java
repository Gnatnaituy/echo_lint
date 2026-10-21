package com.echolint.service;

import com.echolint.dto.AiInvocationDetail;
import com.echolint.dto.AiInvocationFacets;
import com.echolint.dto.AiInvocationFilter;
import com.echolint.dto.AiInvocationSummary;
import com.echolint.entity.AiInvocation;
import com.echolint.exception.BizException;
import com.echolint.repository.AiInvocationRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「AI 对话记录」查询服务（Batch 6，仅 ADMIN）。
 *
 * <p>列表只返回摘要，避免把整段提示词塞进列表响应；详情接口才返回完整 requestJson/responseJson。
 * 数据面包含所有 AI 调用（语义复筛 / 词挖掘 / embedding），因此本页面同时承担 AI 调用审计入口。</p>
 */
@Service
@RequiredArgsConstructor
public class AiInvocationQueryService {

    private final AiInvocationRepository repository;

    public Page<AiInvocationSummary> query(AiInvocationFilter filter, Pageable pageable) {
        return repository.findAll(specification(filter), pageable).map(AiInvocationSummary::from);
    }

    public AiInvocationDetail detail(Long id) {
        AiInvocation entity = repository.findById(id)
                .orElseThrow(() -> new BizException("AI 调用记录不存在: " + id, HttpStatus.NOT_FOUND));
        return AiInvocationDetail.from(entity);
    }

    public AiInvocationFacets facets() {
        List<AiInvocationFacets.ProviderOption> providers = repository.findDistinctProviders().stream()
                .map(row -> new AiInvocationFacets.ProviderOption(
                        row[0] == null ? null : String.valueOf(row[0]),
                        row[1] == null ? null : String.valueOf(row[1])))
                .filter(provider -> StringUtils.hasText(provider.id()))
                .toList();
        return new AiInvocationFacets(
                repository.findDistinctStages(),
                providers,
                repository.findDistinctModels());
    }

    static Specification<AiInvocation> specification(AiInvocationFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter != null) {
                if (StringUtils.hasText(filter.stage())) {
                    predicates.add(cb.equal(root.get("stage"),
                            filter.stage().trim().toUpperCase(Locale.ROOT)));
                }
                if (StringUtils.hasText(filter.providerId())) {
                    predicates.add(cb.equal(cb.lower(root.get("providerId")),
                            filter.providerId().trim().toLowerCase(Locale.ROOT)));
                }
                if (StringUtils.hasText(filter.model())) {
                    predicates.add(cb.like(cb.lower(root.get("model")),
                            "%" + filter.model().trim().toLowerCase(Locale.ROOT) + "%"));
                }
                if (filter.success() != null) {
                    predicates.add(cb.equal(root.get("success"), filter.success()));
                }
                if (filter.recordingId() != null) {
                    predicates.add(cb.equal(root.get("recordingId"), filter.recordingId()));
                }
                if (filter.from() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                            filter.from().atStartOfDay()));
                }
                if (filter.to() != null) {
                    predicates.add(cb.lessThan(root.get("createdAt"),
                            filter.to().plusDays(1).atStartOfDay()));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
