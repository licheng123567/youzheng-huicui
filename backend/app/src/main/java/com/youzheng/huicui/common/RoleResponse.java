package com.youzheng.huicui.common;

import com.youzheng.huicui.security.CurrentSubject;

/**
 * x-response-by-role / 资金双线字段级隔离（BR-M1-06 / BR-M9-11）统一出口。
 * 杜绝各 Controller 各写裁剪逻辑。
 *
 * 关键约束：裁剪在「构造 DTO 时按 viewRole 选择不同 record / 不放某字段」，而非先建全量再删——
 *   服务商 DTO 物理上不含 commInRate / dueTotalCents 等收佣线字段；
 *   平台/物业 DTO（BatchForProperty）物理不含 payOutRate（付佣线）。
 * 契约测试将断言 commInRate / payOutRate 字段在错误角色响应里「不存在」。
 *
 * 视角推导只信 CurrentSubject.orgType，不信任任何客户端入参。
 */
public final class RoleResponse {
    private RoleResponse() {}

    /** 出参视角：由主体 orgType 推导。PLATFORM→PLATFORM, PROPERTY→PROPERTY, PROVIDER→PROVIDER。 */
    public enum ViewRole { PLATFORM, PROPERTY, PROVIDER }

    public static ViewRole of(CurrentSubject s) {
        return ViewRole.valueOf(s.orgType());
    }

    /**
     * Project 二分（对齐 getProject discriminator mapping）：
     *   物业/平台 = PROPERTY_PLATFORM（→ Project record，含收佣线 + 财务汇总）；
     *   服务商   = PROVIDER（→ ProjectForProvider record，物理不含 commInRate/dueTotalCents/repayTotalCents）。
     * 该返回值即作为 viewRole discriminator 显式输出。
     */
    public static String projectViewRole(CurrentSubject s) {
        return "PROVIDER".equals(s.orgType()) ? "PROVIDER" : "PROPERTY_PLATFORM";
    }

    /**
     * Case 列表脱敏判定（BR-M8-09，listCases summary「结案对非平台/物业脱敏」）：
     * 非平台/非物业主体看结案态(SETTLED/WITHDRAWN/BAD_DEBT/VOIDED)案件时 redacted=true，
     * 由 Case DTO 组装时统一施加（ownerName 脱敏占位、contacts/明细占位）；平台/物业 redacted=false。
     */
    public static boolean caseRedacted(CurrentSubject s, String caseStatus) {
        ViewRole v = of(s);
        if (v == ViewRole.PLATFORM || v == ViewRole.PROPERTY) return false;
        return switch (caseStatus) {
            case "SETTLED", "WITHDRAWN", "BAD_DEBT", "VOIDED" -> true;
            default -> false;
        };
    }
}
