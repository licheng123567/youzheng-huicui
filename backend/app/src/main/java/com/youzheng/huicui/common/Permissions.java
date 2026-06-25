package com.youzheng.huicui.common;

import java.util.List;
import java.util.Set;

/**
 * 角色 → 权限点映射（骨架；生产从 permission 表/角色模板加载）。
 *
 * 单一事实来源：登录签发 JWT（AuthController.permissionsOf 委托此处）与
 * M10 权限矩阵端点（getPermissionMatrix 笛卡尔展开 feature×role）共用，杜绝两处漂移。
 * 纯内存计算，无需 DB 种子。
 */
public final class Permissions {
    private Permissions() {}

    /** 系统全部角色（permission-matrix 行/列展开用）。 */
    public static final List<String> ROLES = List.of("SA", "SE", "PL", "PC", "VL", "CO");

    /** 按角色返回代表性权限点集合。 */
    public static Set<String> of(String role) {
        return switch (role) {
            // 平台：派单/再派/开放抢单/作废 + 结算/质检/主数据
            case "SA", "SE" -> Set.of("proj.edit", "batch.import", "case.dispatch", "case.void", "case.close",
                    "payreq.create", "payreq.complete", "qc.review", "qc.escalate", "member.manage", "report.export",
                    "org.manage", "ai.config", "billing.recharge", "settings.manage");   // 平台：建组织/AI配置/充值/系统设置
            // 物业负责人/协调员（+处置/上报质检 BR-M5-07a；+撤案/坏账 BR-M8；+管本组织成员；+采纳作战手册 BR-M5）
            case "PL", "PC" -> Set.of("proj.edit", "reduce.policy.edit", "case.follow", "case.paylink",
                    "case.repay.mark", "case.reduce", "evidence.create", "legal.create", "qc.dispose", "qc.escalate",
                    "case.close", "member.manage", "playbook.adopt");
            // 服务商负责人：承接/拒接/分配/退案 + 处置/上报本商催收员风险 + 管本商成员
            case "VL" -> Set.of("case.accept", "case.assign", "case.return", "cocomm.manage", "payreq.create",
                    "qc.dispose", "qc.escalate", "member.manage");
            // 催收员：抢单/释放/跟进/通话/承诺/工单/缴费链接/标回款
            case "CO" -> Set.of("case.claim", "case.release", "case.follow", "case.call",
                    "case.promise", "case.ticket", "case.paylink", "case.repay.mark", "cocomm.self.view");
            default -> Set.of();
        };
    }
}
