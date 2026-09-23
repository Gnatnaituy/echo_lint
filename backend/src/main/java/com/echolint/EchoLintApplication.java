package com.echolint;

import com.echolint.config.AppProperties;
import com.echolint.config.OpenAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
// proxyTargetClass=true：@Async Bean 一律用 CGLIB 子类代理。
// 否则若某个 @Async Bean 恰好实现了接口（如 ApplicationListener），会被 JDK 动态代理，
// 其它 Bean 按具体类型注入就会失败。
@EnableAsync(proxyTargetClass = true)
@EnableConfigurationProperties({AppProperties.class, OpenAiProperties.class})
public class EchoLintApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoLintApplication.class, args);
    }
}