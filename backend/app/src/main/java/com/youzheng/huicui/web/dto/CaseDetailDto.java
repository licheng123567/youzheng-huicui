package com.youzheng.huicui.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 案件详情聚合（对齐契约 components.schemas.CaseDetail），一次取齐三栏。
 * playbook / preCallStrategy：M2 读阶段返回 null（M5 接入作战手册/AI），见 Controller TODO。
 * availableActions：按当前主体 permissions + case.status 计算的可用操作点字符串数组（驱动前端操作区显隐）。
 */
public record CaseDetailDto(
        @JsonProperty("case") CaseDto caseData,
        List<CaseContactDto> contacts,
        List<CaseActivityDto> timeline,
        CaseProjectRefDto projectRef,
        Object playbook,
        Object preCallStrategy,
        List<String> availableActions,
        // markCodes：MARK_CODES 域启用项(enabled=true)，使 CO/VL 无需访问 platform-scoped /settings 即取 CFG-MARK-CODES(M-01/BR-M4-12)。
        List<Object> markCodes
) {}
