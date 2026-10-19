package com.echolint.service;

import com.echolint.domain.AuditAction;
import com.echolint.entity.AuditLog;
import com.echolint.repository.AuditLogRepository;
import com.echolint.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 操作审计。
 *
 * <p>核心约束：<b>审计写入失败绝不影响主流程</b>。所有写入路径都包在 try/catch 中，
 * 异常只打 warn 日志。操作人取当前登录 principal，IP 取 X-Forwarded-For 首个值或 remoteAddr。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_ACTOR = 64;
    private static final int MAX_ACTION = 32;
    private static final int MAX_TARGET_TYPE = 32;
    private static final int MAX_TARGET_ID = 64;
    private static final int MAX_IP = 64;
    private static final int MAX_DETAIL = 2000;
    private static final String SYSTEM_ACTOR = "system";

    private final AuditLogRepository auditLogRepository;

    /** 以当前登录人身份记录审计（无登录态时为 system） */
    public void record(AuditAction action, String targetType, Object targetId, String detail) {
        recordAs(SecurityUtils.currentUsername().orElse(SYSTEM_ACTOR), action, targetType, targetId, detail);
    }

    /** 指定操作人记录审计（登录/登出等 principal 尚未就绪或即将失效的场景） */
    public void recordAs(String actor, AuditAction action, String targetType, Object targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActor(truncate(StringUtils.hasText(actor) ? actor.trim() : SYSTEM_ACTOR, MAX_ACTOR));
            entry.setAction(truncate(action.name(), MAX_ACTION));
            entry.setTargetType(truncate(targetType, MAX_TARGET_TYPE));
            if (targetId != null) {
                entry.setTargetId(truncate(String.valueOf(targetId), MAX_TARGET_ID));
            }
            entry.setDetail(truncate(detail, MAX_DETAIL));
            entry.setIp(currentIp());
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // 审计失败不得影响业务主流程
            log.warn("写审计日志失败（action={}, actor={}）：{}", action, actor, e.getMessage());
        }
    }

    /** 审计日志分页查询：actor 精确（忽略大小写）、action 精确 */
    public Page<AuditLog> query(String actor, String action, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(actor)) {
                predicates.add(cb.equal(cb.lower(root.get("actor")), actor.trim().toLowerCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return auditLogRepository.findAll(spec, pageable);
    }

    private String currentIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                HttpServletRequest request = servletAttributes.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                String ip = StringUtils.hasText(forwarded)
                        ? forwarded.split(",")[0].trim()
                        : request.getRemoteAddr();
                return truncate(ip, MAX_IP);
            }
        } catch (Exception e) {
            log.debug("解析审计请求 IP 失败：{}", e.getMessage());
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
