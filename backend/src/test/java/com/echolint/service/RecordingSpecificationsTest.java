package com.echolint.service;

import com.echolint.domain.CallDirection;
import com.echolint.domain.RecordingStatus;
import com.echolint.domain.ReviewPriority;
import com.echolint.dto.RecordingFilter;
import com.echolint.entity.Recording;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * 过滤条件拼装：坐席/团队大小写不敏感、方向 UNKNOWN 兼容历史 null、时间 coalesce 回退。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class RecordingSpecificationsTest {

    @Mock
    private Root<Recording> root;
    @Mock
    private CriteriaQuery<?> query;
    @Mock
    private CriteriaBuilder cb;
    @Mock
    private Path<Object> path;
    @Mock
    private Expression<LocalDateTime> coalesced;

    @BeforeEach
    void setUp() {
        lenient().when(root.get(anyString())).thenAnswer(inv -> path);
        lenient().when(cb.lower(any())).thenAnswer(inv -> mock(Expression.class));
        lenient().when(cb.coalesce(any(Expression.class), any(Expression.class)))
                .thenAnswer(inv -> coalesced);
        lenient().when(path.in(anyCollection())).thenAnswer(inv -> mock(Predicate.class));
    }

    private Predicate evaluate(RecordingFilter filter) {
        return RecordingSpecifications.of(filter).toPredicate(root, query, cb);
    }

    @Test
    void metadataFiltersUseLowercaseEquality() {
        RecordingFilter filter = RecordingFilter.of(null, null, null, null,
                " 张三 ", "客服一组", "inbound", null, null);

        evaluate(filter);

        verify(root).get("agentName");
        verify(root).get("teamName");
        verify(root).get("direction");
        verify(cb, atLeastOnce()).lower(any());
        verify(cb).equal(any(Expression.class), eq("张三"));
        verify(cb).equal(any(Expression.class), eq("客服一组"));
        verify(cb, atLeastOnce()).equal(any(), eq(CallDirection.INBOUND));
    }

    @Test
    void unknownDirectionAlsoMatchesLegacyNullRows() {
        RecordingFilter filter = RecordingFilter.of(null, null, null, null,
                null, null, "UNKNOWN", null, null);

        evaluate(filter);

        verify(cb).equal(any(), eq(CallDirection.UNKNOWN));
        verify(cb).isNull(any(Expression.class));
    }

    @Test
    void timeRangeUsesCoalesceOfCallTimeAndUploadTime() {
        RecordingFilter filter = RecordingFilter.of(null, null, null, null, null, null, null,
                "2026-10-01", "2026-10-20");

        evaluate(filter);

        verify(root).get("callTime");
        verify(root).get("uploadTime");
        verify(cb).coalesce(any(Expression.class), any(Expression.class));
        verify(cb).greaterThanOrEqualTo(same(coalesced), eq(LocalDateTime.of(2026, 10, 1, 0, 0)));
        verify(cb).lessThan(same(coalesced), eq(LocalDateTime.of(2026, 10, 21, 0, 0)));
    }

    @Test
    void noTimeRangeSkipsCoalescePredicate() {
        evaluate(RecordingFilter.of(null, null, null, null, null, null, null, null, null));

        verify(cb, never()).coalesce(any(Expression.class), any(Expression.class));
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(LocalDateTime.class));
    }

    @Test
    void statusPriorityAndKeywordFiltersAreStillApplied() {
        RecordingFilter filter = RecordingFilter.of("pending,needs_review", " 退款 ", "alice",
                "normal", null, null, null, null, null);

        evaluate(filter);

        verify(path).in(anyCollection());
        verify(cb, times(2)).like(any(Expression.class), anyString());
        verify(cb).isNull(any(Expression.class));
    }

    @Test
    void statusesWithoutPriorityDoNotProducePriorityPredicate() {
        RecordingFilter filter = RecordingFilter.of("compliant", null, null, null,
                null, null, null, null, null);

        evaluate(filter);

        verify(path).in(anyCollection());
        verify(cb, never()).isNull(any(Expression.class));
    }
}
