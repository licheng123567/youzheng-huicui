package com.youzheng.huicui.web.dto;

/**
 * 录入专家话术请求体（契约 ScriptInput，required:[scene,text]）。
 * source/status 由服务端固定（EXPERT / CANDIDATE，飞轮第一环），不由客户端传入。
 *
 * text —— 话术正文。DDL script_lib 现无正文列，折中落入 variant jsonb {text,state:'CANDIDATE'}
 *   （建议 V911 补 text 列对齐契约）。
 */
public record ScriptInputDto(
        String scene,
        String intent,
        String cohort,
        String text) {
}
