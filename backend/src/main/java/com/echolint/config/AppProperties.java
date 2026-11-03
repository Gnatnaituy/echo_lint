package com.echolint.config;

import java.nio.file.Path;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String uploadDir = "data/uploads";
    private long maxSizeBytes = 200 * 1024 * 1024;
    private List<String> allowedExtensions =
            List.of("mp3", "wav", "m4a", "mp4", "webm", "ogg", "flac", "aac");
    private int pipelinePoolSize = 2;
    private Stereo stereo = new Stereo();
    private Review review = new Review();
    private Admin admin = new Admin();
    private Sample sample = new Sample();
    private Retention retention = new Retention();

    /** 复核工作流配置 */
    @Getter
    @Setter
    public static class Review {
        /** 一条录音需要多少个复核人通过才落最终结论；1 = 单人复核，2 = 双人复核 */
        private int requiredApprovals = 1;
    }

    /** 首次启动引导管理员（仅当用户表为空时使用）。 生产环境请通过 APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD 覆盖默认值。 */
    @Getter
    @Setter
    public static class Admin {
        private String username = "admin";
        private String password = "admin12345";
    }

    /** 生效的复核通过人数（至少 1，避免配置错误导致流程卡死） */
    public int reviewRequiredApprovals() {
        return Math.max(1, review == null ? 1 : review.getRequiredApprovals());
    }

    /** 生效的 DFA 未命中抽样比例：钳制到 0~1，NaN/异常配置按 0（关闭）处理， 避免配置笔误把整条流水线变成「全部抽样」或抽样逻辑异常。 */
    public double dfaMissSampleRate() {
        double rate = sample == null ? 0.0 : sample.getDfaMissRate();
        if (Double.isNaN(rate)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, rate));
    }

    /** 是否开启抽样（供启动日志/系统设置展示） */
    public boolean samplingEnabled() {
        return dfaMissSampleRate() > 0.0;
    }

    /**
     * 合规抽检配置（Batch 2b-2）。
     *
     * <p>DFA 词典初筛未命中的录音默认直接归档为 COMPLIANT；打开抽样后按比例把一部分 「看起来没问题」的录音也送进 AI 语义复筛 / 人工复核，用于发现词典漏检。
     */
    @Getter
    @Setter
    public static class Sample {
        /** DFA 未命中时的抽样比例 0~1（env APP_SAMPLE_DFA_MISS_RATE）；0 = 关闭，1 = 全部抽检 */
        private double dfaMissRate = 0.0;
    }

    /** 双声道（双轨）录音处理：左右声道分离后分别转写，对齐成双栏对话 */
    @Getter
    @Setter
    public static class Stereo {
        /** 关闭后退化为单路转写 */
        private boolean enabled = true;

        /** 左声道说话人名称（呼叫中心惯例：左=坐席） */
        private String leftSpeaker = "坐席";

        /** 右声道说话人名称（右=客户） */
        private String rightSpeaker = "客户";

        /** ffmpeg 分轨超时（秒） */
        private int splitTimeoutSeconds = 300;
    }

    /**
     * 数据保留/清理策略（审计 P2-30）：默认关闭，升级后不会静默删除历史数据。
     *
     * <p>开启后由 {@code DataRetentionService} 每天按保留期分批删除超期行 （ai_invocations / audit_logs /
     * pipeline_logs）。原始录音 uploads 目录不在此列， 需要人工或外部归档策略，避免误删审计证据。
     */
    @Getter
    @Setter
    public static class Retention {
        /** 总开关；默认 false，需显式配置 APP_RETENTION_ENABLED=true 才执行 */
        private boolean enabled = false;

        /** 每日执行时间（Spring cron 6 段：秒 分 时 日 月 周，默认 03:30） */
        private String cron = "0 30 3 * * *";

        /** AI 调用留痕保留天数（含请求/回复 TEXT，通常是容量大头） */
        private int aiInvocationsDays = 180;

        /** 审计日志保留天数（合规审计通常要求更长保留期） */
        private int auditLogsDays = 365;

        /** 流水线日志保留天数 */
        private int pipelineLogsDays = 90;
    }

    /** 保留天数钳制：配置 <=0 时回退默认值，避免笔误把保留期配成 0 直接删全表； 上限 3650 天（10 年），再长也没有删除的意义。 */
    public int retentionAiInvocationsDays() {
        return clampRetentionDays(retention == null ? 0 : retention.getAiInvocationsDays(), 180);
    }

    public int retentionAuditLogsDays() {
        return clampRetentionDays(retention == null ? 0 : retention.getAuditLogsDays(), 365);
    }

    public int retentionPipelineLogsDays() {
        return clampRetentionDays(retention == null ? 0 : retention.getPipelineLogsDays(), 90);
    }

    private static int clampRetentionDays(int configured, int fallback) {
        if (configured <= 0) {
            return fallback;
        }
        return Math.min(3650, configured);
    }

    /** 是否开启数据保留清理（供启动日志/定时任务判断） */
    public boolean retentionEnabled() {
        return retention != null && retention.isEnabled();
    }

    /** 上传目录绝对路径（相对路径基于 JVM 工作目录解析）。 注意：不能传相对路径给 MultipartFile.transferTo，否则会解析到容器临时目录。 */
    public Path absoluteUploadDir() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}
