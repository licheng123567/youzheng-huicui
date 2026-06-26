package com.youzheng.huicui.web.dto;

/**
 * 话术库行（契约 Script）。仅平台可见可管（护城河 BR-M5-06/06a）。
 *
 * Rate 口径（v1.0.3 统一分数；V911 起 DB 直存分数）：promiseRate/repayRate 为 0-1 分数，
 *   DB script_lib.promise_rate/repay_rate 同为 NUMERIC(6,4) 分数（0.4500=45%），无需转换，与契约 Rate 一致。
 *
 * source —— ScriptSourceEnum 码（AI_MINED|EXPERT），原样回传枚举码。
 * status —— ScriptStatusEnum 码（EFFECTIVE|CANDIDATE|RETIRED），原样回传枚举码。
 * variant —— variant jsonb 反序列化为 ScriptVariantDto；DB null 列保持 null。
 */
public record ScriptDto(
        String id,
        String scene,
        String intent,
        String cohort,
        String source,
        int uses,
        Double promiseRate,
        Double repayRate,
        String status,
        ScriptVariantDto variant) {
}
