package com.echolint;

import com.echolint.config.AppProperties;
import com.echolint.config.DiarizationProperties;
import com.echolint.config.NotifyProperties;
import com.echolint.config.ScreenModelProperties;
import com.echolint.config.SsoProperties;
import com.echolint.config.StreamingProperties;
import com.echolint.config.TenantProperties;
import com.echolint.config.WhisperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// proxyTargetClass=true：@Async Bean 一律用 CGLIB 子类代理。
// 否则若某个 @Async Bean 恰好实现了接口（如 ApplicationListener），会被 JDK 动态代理，
// 其它 Bean 按具体类型注入就会失败。
@EnableAsync(proxyTargetClass = true)
// 每日 09:00（Asia/Shanghai）待复核积压检查见 ReviewBacklogScheduler
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, WhisperProperties.class, ScreenModelProperties.class,
        NotifyProperties.class, TenantProperties.class, SsoProperties.class, StreamingProperties.class,
        DiarizationProperties.class})
public class EchoLintApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoLintApplication.class, args);
    }
}