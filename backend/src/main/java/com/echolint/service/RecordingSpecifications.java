package com.echolint.service;

import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.dto.RecordingFilter;
import com.echolint.entity.Recording;
import com.echolint.util.TimeRanges;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 录音列表 / 报表共用的查询条件拼装。
 *
 * <p>时间条件用 {@code coalesce(callTime, uploadTime)}：优先按通话时间过滤，
 * 通话时间为空（历史数据/未填写）时回退上传时间，避免漏掉历史录音。</p>
 */
public final class RecordingSpecifications {

    private RecordingSpecifications() {
    }

    public static Specification<Recording> of(RecordingFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            List<RecordingStatus> statuses = filter.statuses();
            if (statuses != null && !statuses.isEmpty()) {
                ps.add(root.get("status").in(statuses));
            }
            String keyword = filter.keyword();
            if (keyword != null) {
                String kw = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("transcript")), kw),
                        cb.like(cb.lower(root.get("fileName")), kw)));
            }
            String assignedTo = filter.assignedTo();
            if (filter.unassignedOnly()) {
                // 复检队列「未指派」：assignedTo 为空即未认领/未指派
                ps.add(cb.isNull(root.get("assignedTo")));
            } else if (assignedTo != null) {
                ps.add(cb.equal(cb.lower(root.get("assignedTo")), assignedTo.toLowerCase(Locale.ROOT)));
            }
            ReviewPriority priority = filter.priority();
            if (priority != null) {
                // 历史数据 priority 为 null 时按默认值 NORMAL 处理
                ps.add(priority == ReviewPriority.NORMAL
                        ? cb.or(cb.equal(root.get("priority"), priority), cb.isNull(root.get("priority")))
                        : cb.equal(root.get("priority"), priority));
            }
            String agentName = filter.agentName();
            if (agentName != null) {
                ps.add(cb.equal(cb.lower(root.get("agentName")), agentName.toLowerCase(Locale.ROOT)));
            }
            String teamName = filter.teamName();
            if (teamName != null) {
                ps.add(cb.equal(cb.lower(root.get("teamName")), teamName.toLowerCase(Locale.ROOT)));
            }
            CallDirection direction = filter.direction();
            if (direction != null) {
                // UNKNOWN 同时匹配历史数据（direction 为 null 按 UNKNOWN 展示）
                ps.add(direction == CallDirection.UNKNOWN
                        ? cb.or(cb.equal(root.get("direction"), direction), cb.isNull(root.get("direction")))
                        : cb.equal(root.get("direction"), direction));
            }
            boolean hasFrom = filter.from() != null && !TimeRanges.MIN.equals(filter.from());
            boolean hasTo = filter.toExclusive() != null && !TimeRanges.MAX.equals(filter.toExclusive());
            if (hasFrom || hasTo) {
                Expression<LocalDateTime> occurredAt = cb.coalesce(
                        root.<LocalDateTime>get("callTime"), root.<LocalDateTime>get("uploadTime"));
                if (hasFrom) {
                    ps.add(cb.greaterThanOrEqualTo(occurredAt, filter.from()));
                }
                if (hasTo) {
                    ps.add(cb.lessThan(occurredAt, filter.toExclusive()));
                }
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }
}
