package com.echolint.service;

import com.echolint.dto.KnowledgeRequest;
import com.echolint.entity.KnowledgeEntry;
import com.echolint.exception.BizException;
import com.echolint.repository.KnowledgeEntryRepository;
import com.echolint.security.SecurityUtils;
import com.echolint.service.rag.RagIndexService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 知识库 / SOP（Batch 4a）：关键字/分类/启用状态检索与 CRUD。
 *
 * <p>读接口对登录用户开放，写接口由 SecurityConfig 限制为 ADMIN/REVIEWER；
 * 删除为物理删除（条目可由管理员重新录入，无需保留历史版本）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeEntryRepository repository;
    private final RagIndexService ragIndexService;

    /**
     * 分页查询：keyword 模糊匹配标题/正文/标签/来源文件名；category 精确（忽略大小写）；
     * enabled 可空；indexStatus / sourceType 精确（trim + 大写，空忽略）。
     */
    public Page<KnowledgeEntry> query(String keyword, String category, Boolean enabled,
                                      String indexStatus, String sourceType, Pageable pageable) {
        return repository.findAll(specification(keyword, category, enabled, indexStatus, sourceType),
                pageable);
    }

    /**
     * 过滤条件：keyword 模糊匹配标题/正文/标签/来源文件名，category 精确（忽略大小写），
     * enabled 可空，indexStatus / sourceType 精确（trim + 大写，空忽略）。
     *
     * <p>保持 static 便于单测直接拼装 Predicate；sourceFilename 为 NULL 的手工条目在
     * {@code cb.lower(null)} 下不会命中关键字，属预期行为（不使用 native SQL）。</p>
     */
    static Specification<KnowledgeEntry> specification(String keyword, String category, Boolean enabled,
                                                       String indexStatus, String sourceType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("content")), like),
                        cb.like(cb.lower(root.get("tags")), like),
                        cb.like(cb.lower(root.get("sourceFilename")), like)));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(cb.equal(cb.lower(root.get("category")),
                        category.trim().toLowerCase(Locale.ROOT)));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (StringUtils.hasText(indexStatus)) {
                predicates.add(cb.equal(root.get("indexStatus"),
                        indexStatus.trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(sourceType)) {
                predicates.add(cb.equal(root.get("sourceType"),
                        sourceType.trim().toUpperCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public KnowledgeEntry get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("知识库条目不存在: " + id, HttpStatus.NOT_FOUND));
    }

    public KnowledgeEntry create(KnowledgeRequest request) {
        KnowledgeEntry entry = new KnowledgeEntry();
        apply(entry, request);
        entry.setCreatedBy(SecurityUtils.currentUsername().orElse("system"));
        KnowledgeEntry saved = repository.save(entry);
        log.info("知识库条目已创建 id={} title={}", saved.getId(), saved.getTitle());
        // RAG 开启时异步分块+向量化；关闭时 no-op（管理员可在设置页手动重建）
        ragIndexService.enqueueKnowledge(saved.getId());
        return saved;
    }

    public KnowledgeEntry update(Long id, KnowledgeRequest request) {
        KnowledgeEntry entry = get(id);
        apply(entry, request);
        entry.setIndexStatus("PENDING");
        KnowledgeEntry saved = repository.save(entry);
        log.info("知识库条目已更新 id={} title={}", saved.getId(), saved.getTitle());
        ragIndexService.enqueueKnowledge(saved.getId());
        return saved;
    }

    /** 删除并返回被删条目（供控制器写审计详情） */
    public KnowledgeEntry delete(Long id) {
        KnowledgeEntry entry = get(id);
        repository.delete(entry);
        ragIndexService.deleteKnowledgeChunks(id);
        log.info("知识库条目已删除 id={} title={}", id, entry.getTitle());
        return entry;
    }

    private void apply(KnowledgeEntry entry, KnowledgeRequest request) {
        entry.setTitle(requireText(request.title(), "标题", 200));
        entry.setCategory(optionalText(request.category(), "分类", 64));
        entry.setViolationType(optionalText(request.violationType(), "违规类型", 64));
        entry.setTags(optionalText(request.tags(), "标签", 512));
        entry.setContent(optionalText(request.content(), "正文", 100_000));
        entry.setEnabled(request.enabled() == null || request.enabled());
    }

    private static String requireText(String value, String field, int max) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BizException(field + "最长 " + max + " 个字符");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BizException(field + "最长 " + max + " 个字符");
        }
        return trimmed;
    }
}
