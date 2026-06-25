package com.youzheng.huicui.web.dto;

/**
 * 话术优化变体（契约 Script.variant，object|null）。
 * 飞轮第三/六环：A/B 实验产出的候选变体，state 表达其生命周期（CANDIDATE/READY/WINNER/PROMOTED…）。
 *
 * 字段对齐契约：
 *   text   —— 变体文案正文（DDL 无主正文列时，专家录入的正文亦折中暂存于此）。
 *   uplift —— 相对现行的效果提升（契约 Rate=0-1 分数；DB variant.uplift 存百分比 → /100 转换）。
 *   state  —— 变体状态串（自由文本，胜出判据见 promote 端点：WINNER/READY 方可晋升）。
 */
public record ScriptVariantDto(
        String text,
        Double uplift,
        String state) {
}
