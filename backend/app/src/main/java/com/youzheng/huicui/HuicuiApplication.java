package com.youzheng.huicui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 有证慧催 后端入口（最小可运行·契约优先地基）。
 * mvn spring-boot:run → Flyway 自动迁移 → 提供 /v1/me。
 * @EnableScheduling：公海定时器自动到期（CFG-T2/TC，见 ExpiryScheduler）。
 */
@SpringBootApplication
@EnableScheduling
public class HuicuiApplication {
    public static void main(String[] args) {
        SpringApplication.run(HuicuiApplication.class, args);
    }
}
