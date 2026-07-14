package com.youzheng.huicui.app.data.session

/**
 * 权限码 → 中文名。供「我的」页把 permissions[] 显示成人话（此前直接打印 case.call 这种裸码）。
 *
 * **这是第三份手抄的字典**（后端 Permissions.java / 前端 permissions.ts / 这里），没有任何机制保证同源：
 * 契约新增权限点后，这份不会自动跟上，「我的」页就默默退回显示裸码 —— 正是本次要修的问题原样复发。
 * 首版就已经漏了 CO 的 cocomm.self.view 和 PC 的 case.repay.mark（下面补上了）。
 * 正解是让 /me 随 permissions 一起下发 label，或从契约生成这份字典；在那之前，加权限点时记得回来补一行。
 */
object PermissionLabels {
    private val MAP = mapOf(
        "ai.config" to "AI 配置",
        "playbook.adopt" to "采纳作战手册",
        "batch.import" to "批次导入",
        "batch.void" to "批次作废",
        "proj.edit" to "项目编辑",
        "case.dispatch" to "案件派单",
        "case.redispatch" to "案件再派",
        "case.assign" to "案件分配",
        "case.accept" to "案件承接",
        "case.claim" to "案件抢单",
        "sea.view" to "公海查看",
        "case.open" to "开放抢单",
        "case.release" to "释放回公海",
        "case.return" to "退回平台",
        "case.reject" to "拒接/驳回",
        "case.close" to "案件结案",
        "case.void" to "案件作废",
        "case.call" to "拨打/通话",
        "case.follow" to "写跟进",
        "case.promise" to "登记承诺",
        "case.ticket" to "转工单",
        "case.paylink" to "发缴费链接",
        "case.reduce" to "减免",
        "case.repay.mark" to "标记线下回款",
        "cocomm.self.view" to "查看本人提成",
        "ticket.handle" to "工单处理",
        "reduce.approve" to "减免审批",
        "legal.create" to "申请法务文书",
        "evidence.create" to "发起存证",
        "qc.dispose" to "质检处置",
        "qc.review" to "质检复核",
        "qc.escalate" to "质检上报",
        "payreq.create" to "生成支付申请单",
        "payreq.complete" to "完成付款",
        "payreq.send" to "发送支付申请单",
        "payreq.revoke" to "撤销支付申请单",
        "billing.recharge" to "充值",
        "cocomm.manage" to "内催佣金管理",
        "report.export" to "报表导出",
        "settings.manage" to "设置管理",
        "member.manage" to "成员管理",
        "member.create" to "成员创建",
        "org.manage" to "组织管理",
        "org.create" to "组织创建",
    )

    fun label(code: String): String = MAP[code] ?: code
}
