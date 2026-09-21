package com.echolint;

import com.echolint.config.AppProperties;
import com.echolint.config.OpenAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({AppProperties.class, OpenAiProperties.class})
public class EchoLintApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoLintApplication.class, args);
    }
}