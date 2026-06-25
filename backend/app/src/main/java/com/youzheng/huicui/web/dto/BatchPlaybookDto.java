package com.youzheng.huicui.web.dto;

/**
 * 批次级作战手册查询响应（契约 getBatchPlaybook 200）：{source, playbook}。
 *
 * 批次级经 batch.project_id 折叠到 project playbook（DDL playbook 仅 project_id 无 batch_id）。
 * 当前 DDL 无批次级独立存储位 → source 恒 'INHERITED'（对称项目级继承语义 BR-M2-18b）。
 */
public record BatchPlaybookDto(
        String source,           // INHERITED | CUSTOM（当前实现恒 INHERITED）
        PlaybookDto playbook) {
}
