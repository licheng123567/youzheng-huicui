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
            // 物业负责人 PL（项目主数据 owner：+proj.edit 项目档案/协调员/背景/佣金提案 + 减免政策）
            case "PL" -> Set.of("proj.edit", "reduce.policy.edit", "case.follow", "case.paylink",
                    "case.repay.mark", "case.reduce", "reduce.approve", "evidence.create", "legal.create",
                    "qc.dispose", "qc.escalate", "case.close", "member.manage", "playbook.adopt", "ticket.handle",
                    "case.call");
            // 物业协调员 PC（纯协调/作业：项目信息只读——无 proj.edit；作战手册可修正优化——保留 playbook.adopt）
            //   与既定设计一致（BatchDetailView 注释「PC 无 proj.edit 写权」）：PC 不改项目档案/协调员/背景/佣金提案，
            //   仅按 case-actor 做案件作业 + 采纳/优化作战手册。其余（减免政策 reduce.policy.edit 等）暂维持。
            case "PC" -> Set.of("reduce.policy.edit", "case.follow", "case.paylink",
                    "case.repay.mark", "case.reduce", "reduce.approve", "evidence.create", "legal.create",
                    "qc.dispose", "qc.escalate", "case.close", "member.manage", "playbook.adopt", "ticket.handle",
                    "case.call");
                    // 物业侧：处理 CO 转来的工单(ticket.handle) + 减免审批(reduce.approve，端点可达；线下留痕不建待办队列)
                    // case.call：BR-M4-01a 物业(PL/PC)可获取/回填本物业案件通话录音并查看 AI 复盘（case-actor 行级裁剪仅本物业）
            // 服务商负责人：承接/拒接/分配/退案 + 处置/上报本商催收员风险 + 管本商成员
            case "VL" -> Set.of("case.accept", "case.assign", "case.return", "cocomm.manage", "payreq.create",
                    "qc.dispose", "qc.escalate", "member.manage");
            // 催收员：抢单/释放/跟进/通话/承诺/开工单(case.ticket)/缴费链接。回款登记/冲正属 PC/SA，CO 无权(矩阵:58-59)
            case "CO" -> Set.of("case.claim", "case.release", "case.follow", "case.call",
                    "case.promise", "case.ticket", "case.paylink", "cocomm.self.view");
            default -> Set.of();
        };
    }
}
