package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 案件详情·项目资料栏（对齐契约 CaseDetail.projectRef）。
 * contractType←project.contract_type；feeStd 由 project.fee_rows(jsonb [{biz,std}]) 汇总成展示串；
 * payInfo←project.pay_info；reduceTiers←reduce_tier(project_id 且 batch_id IS NULL)。
 */
public record CaseProjectRefDto(
        String contractType,
        String feeStd,
        String payInfo,
        List<CaseReduceTierDto> reduceTiers
) {}
