package com.echolint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String uploadDir = "data/uploads";
    private long maxSizeBytes = 200 * 1024 * 1024;
    private List<String> allowedExtensions = List.of("mp3", "wav", "m4a", "mp4", "webm", "ogg", "flac", "aac");
    private int pipelinePoolSize = 2;
    private Stereo stereo = new Stereo();
    private Review review = new Review();
    private Admin admin = new Admin();
    private Sample sample = new Sample();

    /**
     * 复核工作流配置
     */
    @Getter
    @Setter
    public static class Review {
        /** 一条录音需要多少个复核人通过才落最终结论；1 = 单人复核，2 = 双人复核 */
        private int requiredApprovals = 1;
    }

    /**
     * 首次启动引导管理员（仅当用户表为空时使用）。
     * 生产环境请通过 APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD 覆盖默认值。
     */
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

    /**
     * 生效的 DFA 未命中抽样比例：钳制到 0~1，NaN/异常配置按 0（关闭）处理，
     * 避免配置笔误把整条流水线变成「全部抽样」或抽样逻辑异常。
     */
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
     * <p>DFA 词典初筛未命中的录音默认直接归档为 COMPLIANT；打开抽样后按比例把一部分
     * 「看起来没问题」的录音也送进 AI 语义复筛 / 人工复核，用于发现词典漏检。</p>
     */
    @Getter
    @Setter
    public static class Sample {
        /** DFA 未命中时的抽样比例 0~1（env APP_SAMPLE_DFA_MISS_RATE）；0 = 关闭，1 = 全部抽检 */
        private double dfaMissRate = 0.0;
    }

    /**
     * 双声道（双轨）录音处理：左右声道分离后分别转写，对齐成双栏对话
     */
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
     * 上传目录绝对路径（相对路径基于 JVM 工作目录解析）。
     * 注意：不能传相对路径给 MultipartFile.transferTo，否则会解析到容器临时目录。
     */
    public Path absoluteUploadDir() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}