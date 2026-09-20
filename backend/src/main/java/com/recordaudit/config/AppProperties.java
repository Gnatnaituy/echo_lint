package com.recordaudit.config;

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

    /**
     * 上传目录绝对路径（相对路径基于 JVM 工作目录解析）。
     * 注意：不能传相对路径给 MultipartFile.transferTo，否则会解析到容器临时目录。
     */
    public Path absoluteUploadDir() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}