package com.echolint.service;

import com.echolint.config.AppProperties;
import com.echolint.domain.AuditAction;
import com.echolint.repository.AiInvocationRepository;
import com.echolint.repository.AuditLogRepository;
import com.echolint.repository.PipelineLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 数据保留/清理任务（审计 P2-30）。
 *
 * <p>解决的问题：ai_invocations（含完整 prompt/回复 TEXT）、audit_logs、pipeline_logs 只增不减，
 * 随使用时间无限膨胀，最终拖慢查询并占满磁盘。本任务默认<b>关闭</b>（{@code app.retention.enabled=false}），
 * 避免升级后静默删除任何历史数据；开启后每天按保留期清理超期行。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li>分批删除：每批最多 {@value #BATCH_SIZE} 行（先查 id 再批量删），避免一条大 DELETE 长时间持锁、膨胀
 *       WAL；每批有独立事务（SimpleJpaRepository 的批量删除自带事务）；
 *   <li>单表单次运行有批次上限，异常数据量不会把任务拖成小时级；
 *   <li>任一张表失败只 WARN，继续清理其余表，绝不影响应用主流程；
 *   <li>清理完成后写一条审计（复用现有 {@link AuditAction#SETTINGS_UPDATE}，本任务不新增 枚举值以避免与其它并行任务修改 AuditAction 冲突）；
 *   <li>原始录音 uploads 目录<b>不做</b>自动删除（审计证据，需人工/外部归档策略）。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    /** 每批删除行数 */
    static final int BATCH_SIZE = 500;

    /** 单表单次运行最多删除批次数（500 × 200 = 10 万行/天/表） */
    static final int MAX_BATCHES_PER_TABLE = 200;

    /** 审计目标类型 */
    static final String TARGET_TYPE = "DATA_RETENTION";

    private final AppProperties appProperties;
    private final AiInvocationRepository aiInvocations;
    private final AuditLogRepository auditLogs;
    private final PipelineLogRepository pipelineLogs;
    private final AuditService auditService;

    /**
     * 每日清理入口（默认 03:30，可用 app.retention.cron 覆盖）。
     *
     * @return 本次实际删除的行数（方便日志/单测断言；Spring 调度忽略返回值）
     */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public int purgeExpired() {
        if (!appProperties.retentionEnabled()) {
            log.debug("数据保留清理未开启（app.retention.enabled=false），跳过");
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime aiCutoff = now.minusDays(appProperties.retentionAiInvocationsDays());
        LocalDateTime auditCutoff = now.minusDays(appProperties.retentionAuditLogsDays());
        LocalDateTime logCutoff = now.minusDays(appProperties.retentionPipelineLogsDays());

        int aiDeleted =
                purge(
                        "ai_invocations",
                        aiCutoff,
                        (cutoff, page) -> aiInvocations.findIdsByCreatedAtBefore(cutoff, page),
                        aiInvocations::deleteAllByIdInBatch);
        int auditDeleted =
                purge(
                        "audit_logs",
                        auditCutoff,
                        (cutoff, page) -> auditLogs.findIdsByCreatedAtBefore(cutoff, page),
                        auditLogs::deleteAllByIdInBatch);
        int logDeleted =
                purge(
                        "pipeline_logs",
                        logCutoff,
                        (cutoff, page) -> pipelineLogs.findIdsByCreatedAtBefore(cutoff, page),
                        pipelineLogs::deleteAllByIdInBatch);

        int total = aiDeleted + auditDeleted + logDeleted;
        if (total == 0) {
            log.info(
                    "数据保留清理：无超期数据（AI 留痕 {} 天 / 审计日志 {} 天 / 流水线日志 {} 天）",
                    appProperties.retentionAiInvocationsDays(),
                    appProperties.retentionAuditLogsDays(),
                    appProperties.retentionPipelineLogsDays());
            return 0;
        }
        log.info(
                "数据保留清理完成：ai_invocations={} 行 / audit_logs={} 行 / pipeline_logs={} 行"
                        + "（保留 {} 天 / {} 天 / {} 天）",
                aiDeleted,
                auditDeleted,
                logDeleted,
                appProperties.retentionAiInvocationsDays(),
                appProperties.retentionAuditLogsDays(),
                appProperties.retentionPipelineLogsDays());
        // 清理动作留痕：复用现有动作 SETTINGS_UPDATE（本任务不新增 AuditAction 枚举）
        auditService.record(
                AuditAction.SETTINGS_UPDATE,
                TARGET_TYPE,
                "retention",
                "数据保留清理：ai_invocations="
                        + aiDeleted
                        + "，audit_logs="
                        + auditDeleted
                        + "，pipeline_logs="
                        + logDeleted);
        return total;
    }

    /** 分批清理单张表：每批先查最多 {@value #BATCH_SIZE} 个过期 id，再批量删除。 异常只 WARN 并返回已删除行数，不向调度器抛出。 */
    private int purge(
            String table,
            LocalDateTime cutoff,
            BiFunction<LocalDateTime, Pageable, List<Long>> idFinder,
            Consumer<List<Long>> batchDeleter) {
        int deleted = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_TABLE; batch++) {
                List<Long> ids = idFinder.apply(cutoff, PageRequest.of(0, BATCH_SIZE));
                if (ids.isEmpty()) {
                    break;
                }
                batchDeleter.accept(ids);
                deleted += ids.size();
                if (ids.size() < BATCH_SIZE) {
                    break;
                }
            }
            if (deleted >= MAX_BATCHES_PER_TABLE * BATCH_SIZE) {
                log.warn(
                        "数据保留清理 {} 达到单次上限 {} 行，剩余超期数据次日继续清理",
                        table,
                        MAX_BATCHES_PER_TABLE * BATCH_SIZE);
            }
        } catch (Exception e) {
            log.warn("数据保留清理失败 table={} cutoff={}：{}", table, cutoff, e.getMessage());
        }
        return deleted;
    }
}
