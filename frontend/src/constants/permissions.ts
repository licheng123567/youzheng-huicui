// 权限点 code → 中文标签。展示用(工作台当前主体/个人资料/成员授权勾选);提交仍用原 code。
// 全量来自后端权限目录 + 各角色 /me.permissions 实测(SA/CO/PC/VL)。未命中回退原 code。
export const PERM_LABEL: Record<string, string> = {
  // AI / 话术
  'ai.config': 'AI 配置',
  'ai.config.update': 'AI 配置更新',
  'ai.script.create': 'AI 话术创建',
  'playbook.adopt': '采纳作战手册',
  'playbook.adopt.batch': '批次作战手册采纳',
  // 批次 / 项目
  'batch.import': '批次导入',
  'batch.void': '批次作废',
  'proj.edit': '项目编辑',
  // 案件流转
  'case.dispatch': '案件派单',
  'case.redispatch': '案件再派',
  'case.assign': '案件分配',
  'case.accept': '案件承接',
  'case.claim': '案件抢单',
  'case.open': '开放抢单',
  'case.release': '释放回公海',
  'case.return': '退回平台',
  'case.reject': '拒接/驳回',
  'case.close': '案件结案',
  'case.void': '案件作废',
  // 案件作业
  'case.call': '拨打/通话',
  'case.follow': '写跟进',
  'case.promise': '登记承诺',
  'case.ticket': '转工单',
  'case.paylink': '发缴费链接',
  'case.reduce': '减免',
  'case.repay.mark': '标记回款',
  'ticket.handle': '工单处理',
  // 减免审批
  'reduce.approve': '减免审批',
  'reduce.policy.edit': '减免政策编辑',
  // 法务 / 存证
  'legal.create': '申请法务文书',
  'evidence.create': '发起存证',
  // 质检
  'qc.dispose': '质检处置',
  'qc.review': '质检复核',
  'qc.escalate': '质检上报',
  // 结算 / 计费
  'payreq.create': '生成支付申请单',
  'payreq.complete': '完成付款',
  'payreq.send': '发送支付申请单',
  'payreq.revoke': '撤销支付申请单',
  'billing.recharge': '充值',
  'cocomm.manage': '内催佣金管理',
  'cocomm.self.view': '我的佣金自查',
  // 报表 / 设置 / 成员 / 组织
  'report.export': '报表导出',
  'settings.manage': '设置管理',
  'member.manage': '成员管理',
  'member.create': '成员创建',
  'org.manage': '组织管理',
  'org.create': '组织创建',
}

export function permLabel(code: string): string {
  return PERM_LABEL[code] ?? code
}
