package com.youzheng.huicui.web.dto;

import java.util.Map;

/** 三方通道配置 DTO（v1.23.0·V936）。密钥只以 last4 掩码出现，明文永不出接口。 */
public final class IntegrationDtos {

    /**
     * @param settings      非密字段明文（baseUrl/smsBaseUrl/videoBaseUrl）
     * @param secretsMasked 密钥掩码（****abc；未配置→null）
     * @param source        实际生效的配置来源：DB(后台维护) / ENV(环境变量) / NONE(未配置)
     * @param cryptoReady   主密钥(HUICUI_CRYPTO_KEY)是否就绪；false 时后台存不了密钥（写侧 409）
     */
    public record IntegrationDto(
            String provider,
            String name,
            boolean enabled,
            boolean configured,
            String source,
            Map<String, String> settings,
            Map<String, String> secretsMasked,
            String updatedAt,
            String updatedByName,
            boolean cryptoReady
    ) {}

    private IntegrationDtos() {}
}
