package com.youzheng.huicui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 有证慧催 后端入口（最小可运行·契约优先地基）。
 * mvn spring-boot:run → Flyway 自动迁移（V1/V2 共 34 表）→ 提供 /v1/me。
 * 横切层（鉴权/数据范围/统一错误/幂等/JWT/按角色响应裁剪）与各业务模块在后续里程碑叠加。
 */
@SpringBootApplication
public class HuicuiApplication {
    public static void main(String[] args) {
        SpringApplication.run(HuicuiApplication.class, args);
    }
}
