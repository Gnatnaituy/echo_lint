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
    private long maxSizeBytes = 25 * 1024 * 1024;
    private List<String> allowedExtensions = List.of("mp3", "wav", "m4a", "mp4", "webm", "ogg", "flac", "aac");
    private int pipelinePoolSize = 2;
    private Stereo stereo = new Stereo();

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