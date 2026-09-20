package com.recordaudit;

import com.recordaudit.config.AppProperties;
import com.recordaudit.config.OpenAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({AppProperties.class, OpenAiProperties.class})
public class RecordAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordAuditApplication.class, args);
    }
}