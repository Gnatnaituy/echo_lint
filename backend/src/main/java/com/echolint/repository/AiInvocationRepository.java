package com.echolint.repository;

import com.echolint.entity.AiInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiInvocationRepository extends JpaRepository<AiInvocation, Long>,
        JpaSpecificationExecutor<AiInvocation> {

    /** 某条录音的全部 AI 调用，按时间正序（复筛在前、挖掘在后） */
    List<AiInvocation> findByRecordingIdOrderByIdAsc(Long recordingId);

    // ---------------- AI 对话记录页面（Batch 6） ----------------

    /** 筛选下拉：出现过的阶段 */
    @Query("select distinct i.stage from AiInvocation i where i.stage is not null order by i.stage")
    List<String> findDistinctStages();

    /** 筛选下拉：供应商（id + 展示名） */
    @Query("select distinct i.providerId, i.providerLabel from AiInvocation i "
            + "where i.providerId is not null order by i.providerId")
    List<Object[]> findDistinctProviders();

    /** 筛选下拉：模型 */
    @Query("select distinct i.model from AiInvocation i where i.model is not null order by i.model")
    List<String> findDistinctModels();

    /*
     * 配额与成本统计（Batch 3a）：只统计成功调用 —— 失败调用没有 usage，也没有费用。
     */

    /** 当日已用 token（输入 + 输出），用于每日配额校验 */
    @Query("select coalesce(sum(coalesce(i.inputTokens, 0) + coalesce(i.outputTokens, 0)), 0) "
            + "from AiInvocation i where i.createdAt >= :from")
    Long sumTokensSince(@Param("from") LocalDateTime from);

    @Query("select count(i) from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to")
    long countSuccessfulBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select coalesce(sum(i.inputTokens), 0) from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumInputTokensBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select coalesce(sum(i.outputTokens), 0) from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumOutputTokensBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select coalesce(sum(i.costMicros), 0) from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumCostMicrosBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按自然日聚合（MySQL DATE()；行：java.sql.Date, count, inputTokens, outputTokens, costMicros） */
    @Query(value = "SELECT DATE(created_at) AS d, COUNT(*) AS c, "
            + "COALESCE(SUM(input_tokens), 0) AS i, COALESCE(SUM(output_tokens), 0) AS o, "
            + "COALESCE(SUM(cost_micros), 0) AS m "
            + "FROM ai_invocations WHERE success = 1 AND created_at >= :from AND created_at < :to "
            + "GROUP BY DATE(created_at) ORDER BY d", nativeQuery = true)
    List<Object[]> aggregateByDayBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按供应商聚合（行：providerId, count, inputTokens, outputTokens, costMicros） */
    @Query("select i.providerId, count(i), coalesce(sum(i.inputTokens), 0), "
            + "coalesce(sum(i.outputTokens), 0), coalesce(sum(i.costMicros), 0) "
            + "from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to "
            + "group by i.providerId order by count(i) desc, i.providerId asc")
    List<Object[]> aggregateByProviderBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按模型聚合（行：model, count, inputTokens, outputTokens, costMicros） */
    @Query("select i.model, count(i), coalesce(sum(i.inputTokens), 0), "
            + "coalesce(sum(i.outputTokens), 0), coalesce(sum(i.costMicros), 0) "
            + "from AiInvocation i where i.success = true "
            + "and i.createdAt >= :from and i.createdAt < :to "
            + "group by i.model order by count(i) desc, i.model asc")
    List<Object[]> aggregateByModelBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
