package com.echolint.repository;

import com.echolint.entity.AiInvocation;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiInvocationRepository
        extends JpaRepository<AiInvocation, Long>, JpaSpecificationExecutor<AiInvocation> {

    /**
     * 列表摘要投影：只含列表页需要的列，**不含 request_json / response_json** 两个 TEXT 大字段，
     * 避免列表分页把整段提示词从数据库读出来再丢弃（Batch 6 收尾）。
     */
    interface SummaryProjection {
        Long getId();

        Long getRecordingId();

        String getStage();

        String getProviderId();

        String getProviderLabel();

        String getModel();

        String getEndpoint();

        Boolean getSuccess();

        Boolean getDegraded();

        Integer getLatencyMs();

        Integer getRequestChars();

        Integer getResponseChars();

        Integer getInputTokens();

        Integer getOutputTokens();

        Long getCostMicros();

        String getErrorMessage();

        LocalDateTime getCreatedAt();
    }

    /**
     * 「AI 对话记录」列表分页（投影查询）：动态筛选用「null 则不生效」的 JPQL 条件表达， 与 {@code AiInvocationQueryService}
     * 的筛选语义保持一致（stage 大写、provider 小写等由调用方归一化）。
     */
    @Query(
            "select i.id as id, i.recordingId as recordingId, i.stage as stage, i.providerId as"
                + " providerId, i.providerLabel as providerLabel, i.model as model, i.endpoint as"
                + " endpoint, i.success as success, i.degraded as degraded, i.latencyMs as"
                + " latencyMs, i.requestChars as requestChars, i.responseChars as responseChars,"
                + " i.inputTokens as inputTokens, i.outputTokens as outputTokens, i.costMicros as"
                + " costMicros, i.errorMessage as errorMessage, i.createdAt as createdAt from"
                + " AiInvocation i where (:stage is null or i.stage = :stage) and (:providerId is"
                + " null or lower(i.providerId) = :providerId) and (:modelPattern is null or"
                + " lower(i.model) like :modelPattern) and (:success is null or i.success ="
                + " :success) and (:recordingId is null or i.recordingId = :recordingId) and"
                + " (:fromTime is null or i.createdAt >= :fromTime) and (:toTime is null or"
                + " i.createdAt < :toTime)")
    Page<SummaryProjection> findSummaries(
            @Param("stage") String stage,
            @Param("providerId") String providerId,
            @Param("modelPattern") String modelPattern,
            @Param("success") Boolean success,
            @Param("recordingId") Long recordingId,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime,
            Pageable pageable);

    /** 某条录音的全部 AI 调用，按时间正序（复筛在前、挖掘在后） */
    List<AiInvocation> findByRecordingIdOrderByIdAsc(Long recordingId);

    /** 数据保留清理（P2-30）：按创建时间查过期行 id，每次只取一页（≤500）， 由调用方逐批删除，避免一条大 DELETE 长时间持锁。 */
    @Query("select i.id from AiInvocation i where i.createdAt < :cutoff order by i.id")
    List<Long> findIdsByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    // ---------------- AI 对话记录页面（Batch 6） ----------------

    /** 筛选下拉：出现过的阶段 */
    @Query("select distinct i.stage from AiInvocation i where i.stage is not null order by i.stage")
    List<String> findDistinctStages();

    /** 筛选下拉：供应商（id + 展示名） */
    @Query(
            "select distinct i.providerId, i.providerLabel from AiInvocation i "
                    + "where i.providerId is not null order by i.providerId")
    List<Object[]> findDistinctProviders();

    /** 筛选下拉：模型 */
    @Query("select distinct i.model from AiInvocation i where i.model is not null order by i.model")
    List<String> findDistinctModels();

    /*
     * 配额与成本统计（Batch 3a）：只统计成功调用 —— 失败调用没有 usage，也没有费用。
     */

    /** 当日已用 token（输入 + 输出），用于每日配额校验 */
    @Query(
            "select coalesce(sum(coalesce(i.inputTokens, 0) + coalesce(i.outputTokens, 0)), 0) "
                    + "from AiInvocation i where i.createdAt >= :from")
    Long sumTokensSince(@Param("from") LocalDateTime from);

    @Query(
            "select count(i) from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to")
    long countSuccessfulBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "select coalesce(sum(i.inputTokens), 0) from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumInputTokensBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "select coalesce(sum(i.outputTokens), 0) from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumOutputTokensBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "select coalesce(sum(i.costMicros), 0) from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to")
    Long sumCostMicrosBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按自然日聚合（PostgreSQL DATE()；success 为 boolean，不能用 = 1；行：java.sql.Date, count, inputTokens,
     * outputTokens, costMicros）
     */
    @Query(
            value =
                    "SELECT DATE(created_at) AS d, COUNT(*) AS c, COALESCE(SUM(input_tokens), 0) AS"
                        + " i, COALESCE(SUM(output_tokens), 0) AS o, COALESCE(SUM(cost_micros), 0)"
                        + " AS m FROM ai_invocations WHERE success = true AND created_at >= :from"
                        + " AND created_at < :to GROUP BY DATE(created_at) ORDER BY d",
            nativeQuery = true)
    List<Object[]> aggregateByDayBetween(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按供应商聚合（行：providerId, count, inputTokens, outputTokens, costMicros） */
    @Query(
            "select i.providerId, count(i), coalesce(sum(i.inputTokens), 0), "
                    + "coalesce(sum(i.outputTokens), 0), coalesce(sum(i.costMicros), 0) "
                    + "from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to "
                    + "group by i.providerId order by count(i) desc, i.providerId asc")
    List<Object[]> aggregateByProviderBetween(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按模型聚合（行：model, count, inputTokens, outputTokens, costMicros） */
    @Query(
            "select i.model, count(i), coalesce(sum(i.inputTokens), 0), "
                    + "coalesce(sum(i.outputTokens), 0), coalesce(sum(i.costMicros), 0) "
                    + "from AiInvocation i where i.success = true "
                    + "and i.createdAt >= :from and i.createdAt < :to "
                    + "group by i.model order by count(i) desc, i.model asc")
    List<Object[]> aggregateByModelBetween(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
