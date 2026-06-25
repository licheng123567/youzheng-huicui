package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * M2 projects 资源读端点 DTO 集（对齐冻结契约 openapi-core.yaml 的
 * Project / ProjectForProvider / ReduceTier / FeeRow / Litigation / CoordinatorRef schema）。
 *
 * 资金双线字段级隔离（BR-M1-06 / BR-M9-11）以「不同 record 物理隔离字段」落地：
 *   - {@link Project}（物业/平台视角，viewRole=PROPERTY_PLATFORM）含 commInRate + 财务汇总 dueTotalCents/repayTotalCents；
 *   - {@link ProjectForProvider}（服务商视角，viewRole=PROVIDER）物理不含 commInRate / 财务汇总。
 * 契约测试断言 commInRate 在 PROVIDER 响应里「不存在」——故服务商 DTO 不声明该字段，而非置 null。
 *
 * 金额列 *_cents 原样以「分(integer)」返回（契约 Money=amount_cents），不转元。
 */
public final class ProjectDtos {
    private ProjectDtos() {}

    /** 收费标准行（契约 ProjectInput.feeRows item / fee_rows jsonb [{biz,std}]）。 */
    public record FeeRow(String biz, String std) {}

    /** 协调员引用（契约 CoordinatorRef；PC↔项目 多对多 BR-M2-13）。 */
    public record CoordinatorRef(String id, String name) {}

    /** 诉讼要素（契约 Project/ProjectForProvider.litigation；由 project.credit_code/legal/addr 组装）。 */
    public record Litigation(String creditCode, String legal, String addr) {}

    /** 减免阶梯（契约 ReduceTier；reduce_tier 表项目级 batch_id IS NULL）。capCents 可空=无上限。 */
    public record ReduceTier(String discount, Long capCents, Boolean waivePenalty, String decide) {}

    /**
     * 物业/平台视角项目档案（契约 Project，allOf=ProjectInput + 出参补充字段）。
     * viewRole 固定 "PROPERTY_PLATFORM" 作为 oneOf discriminator 显式输出。
     * 列表（listProjects）与详情（getProject 物业/平台分支）共用此 record；
     *   列表场景 coordinators/reduceTiers 可为 null（契约「列表可省略」），详情必给。
     */
    public record Project(
            String viewRole,
            String id,
            String name,
            String area,
            String province,
            String city,
            String district,
            String propCompany,
            String contractType,
            List<FeeRow> feeRows,
            String feeCycle,
            String penalty,
            String payInfo,
            Double commInRate,          // 收佣线（IN）·百分比 BR-M9-01a；服务商视角物理不含
            String org,                 // 归属物业短名（<- project.org_name）
            String status,
            Long dueTotalCents,         // case 聚合 sum(due_cents)
            Long repayTotalCents,       // 回款聚合（M2 占位 TODO 对账模块接入）
            List<CoordinatorRef> coordinators,
            List<ReduceTier> reduceTiers,
            Litigation litigation
    ) {}

    /**
     * 服务商视角项目档案（契约 ProjectForProvider）=催收依据，物理不含 commInRate / 财务汇总。
     * feeStd 由 fee_rows 汇总成展示串（契约 feeStd:string，非 feeRows 数组）。
     */
    public record ProjectForProvider(
            String viewRole,            // 固定 "PROVIDER"
            String id,
            String name,
            String area,
            String propCompany,
            String contractType,
            String feeStd,              // fee_rows 汇总展示串
            String feeCycle,
            String penalty,
            String payInfo,
            List<ReduceTier> reduceTiers,
            Litigation litigation,
            String status
    ) {}
}
