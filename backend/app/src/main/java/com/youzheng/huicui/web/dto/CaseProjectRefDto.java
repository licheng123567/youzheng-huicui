package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 案件详情·项目资料栏（对齐契约 CaseDetail.projectRef，高保真§项目资料 Tab）。
 * contractType←project.contract_type；contractName←project.contract_name；
 * servicePeriod←project.service_period；feeCycle←project.fee_cycle；
 * feeStd 由 project.fee_rows(jsonb [{biz,std}]) 汇总成展示串；
 * feeItems←project.penalty；corpAccount←project.corp_account；
 * wxQrUrl←project.wx_qr_url；payInfo←project.pay_info；
 * reducePolicy←project.reduce_policy；reduceTiers←reduce_tier(project_id 且 batch_id IS NULL)；
 * batchNo←batch.no；commInRate←batch.comm_in_rate（收佣比例，物业↔平台线）；
 * payOutRate←batch.pay_out_rate（付佣比例，平台↔服务商线）；commInConfirmed←batch.comm_in_confirmed。
 * 资金双线隔离(BR-M9-11)：物业视角 payOutRate=null、服务商视角 commInRate=null（服务端字段级省略）。
 */
public record CaseProjectRefDto(
        String contractType,
        String contractName,
        String servicePeriod,
        String feeCycle,
        String feeStd,
        String feeItems,
        String corpAccount,
        String wxQrUrl,
        String payInfo,
        String reducePolicy,
        List<CaseReduceTierDto> reduceTiers,
        String batchNo,
        String commInRate,
        String payOutRate,
        Boolean commInConfirmed
) {}
