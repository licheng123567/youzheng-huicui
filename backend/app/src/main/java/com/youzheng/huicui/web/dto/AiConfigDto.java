package com.youzheng.huicui.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 平台 AI 能力配置中心（契约 AiConfig）。x-data-scope=platform，密钥密文 BR-M5-13。
 * 存储：settings 表 domain='AI' 最新版本，配置体落 value jsonb（V910 扩 CHECK + 加 value 列）。
 *
 * 嵌套结构对齐契约：
 *   llm{provider,model,temperature,maxTokens[,apiKey]}
 *   asr{provider,model,hotwords[][,apiKey]}
 *   prompts{preCall,postReview,riskRules}
 *   flywheel{autoIterate,trigger,adoptMode(PlaybookAdoptModeEnum),liveHint(恒false OQ-M5-1)}
 *
 * 密钥（llm.apiKey/asr.apiKey）：契约未显式声明，但 BR-M5-13 要求密文存储；
 *   GET 时掩码为 "****" 不回明文；PUT 时若仍为掩码占位则保留旧密文不覆盖。
 *   用 @JsonIgnoreProperties(ignoreUnknown=true) 容忍 DB 中存在的额外字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiConfigDto(
        Llm llm,
        Asr asr,
        Prompts prompts,
        Flywheel flywheel) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Llm(String provider, String model, Double temperature, Integer maxTokens, String apiKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Asr(String provider, String model, List<String> hotwords, String apiKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prompts(String preCall, String postReview, String riskRules) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flywheel(Boolean autoIterate, String trigger, String adoptMode, Boolean liveHint) {}
}
