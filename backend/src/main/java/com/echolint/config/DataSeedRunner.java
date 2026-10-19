package com.echolint.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * 预置数据（敏感词词典 + 初始语料）加载。
 *
 * <p>为什么不用 Boot 的 {@code spring.sql.init}：Flyway 与
 * {@code spring.jpa.defer-datasource-initialization: true} 在 Boot 3.3 会形成
 * EntityManagerFactory / Flyway 初始化器的 depends-on 环，启动失败。
 * 这里改为在应用启动完成（JPA 已建表）后执行 {@code classpath:data.sql}，
 * 脚本本身幂等（INSERT IGNORE / WHERE NOT EXISTS），每次启动重复执行安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeedRunner implements ApplicationRunner {

    private static final String SEED_SCRIPT = "data.sql";

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        ClassPathResource script = new ClassPathResource(SEED_SCRIPT);
        if (!script.exists()) {
            log.warn("未找到预置数据脚本 {}，跳过", SEED_SCRIPT);
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(script);
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
        log.info("预置数据 {} 已执行（幂等）", SEED_SCRIPT);
    }
}
