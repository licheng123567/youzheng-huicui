// 角色导航单一真源：菜单（AppLayout 渲染）与路由越权守卫（router beforeEach）共用，杜绝两处漂移。
// 菜单按原型「每个角色 nav」1:1（docs/ui/高保真 roles[role].nav）。view-key → 实现端真实路由；
// 合并/未建独立页的 key 映射为 null（不进菜单）。

export type NavItem = string | { group: string }

export const KEY2PATH: Record<string, string | null> = {
  workbench: '/dashboard', dispatch: '/batches', platformSea: '/sea', providerSea: '/sea',
  projects: '/projects', cases: '/cases', myCases: '/cases', callLog: '/call-records',
  qc: '/risks', evidence: '/evidence',
  // 结算三线已拆独立页（收佣对账/付佣对账/内催佣金）
  reconIn: '/settlement', reconOut: '/settlement-out', coCommission: '/co-commission',
  // 计费三项已拆（用量/充值/短信）
  billing: '/billing', recharge: '/recharge', sms: '/sms',
  members: '/members', orgMgmt: '/org-mgmt', settings: '/settings', playbookLib: '/script-lib',
  reports: '/reports', audit: '/audit-log', inbox: '/notifications',
  legal: '/legal', myStats: '/my-stats', myLinks: '/my-links',
  // 外勤作业 App 下载与使用说明：菜单对所有角色开放（管理角色需要把安装链接转发给手下作业人员），
  // 但 App 本身只对催收员(CO)与物业协调员(PC)开放（BR-APP-01），页面内文案按角色分叉。
  appDownload: '/app-download',
}

export const PATH2LABEL: Record<string, string> = {
  '/dashboard': '工作台', '/batches': '撮合派单', '/sea': '案件公海', '/projects': '项目管理',
  '/cases': '案件管理', '/call-records': '通话记录', '/risks': '质检/风控', '/evidence': '存证管理',
  '/settlement': '收佣对账', '/settlement-out': '付佣对账', '/co-commission': '催收员佣金',
  '/billing': '计费明细', '/recharge': '充值中心', '/sms': '短信通道',
  '/members': '成员管理', '/org-mgmt': '组织管理', '/settings': '参数配置', '/script-lib': '平台话术库',
  '/reports': '经营报表', '/audit-log': '操作日志', '/notifications': '消息中心',
  '/legal': '送达管理', '/my-stats': '我的业绩', '/my-links': '缴费链接',
  '/app-download': '外勤作业 App',
}

export const NAV_BY_ROLE: Record<string, NavItem[]> = {
  SA: [{ group: '业务' }, 'workbench', 'dispatch', 'platformSea', 'projects', 'cases', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'reconOut', 'billing', 'recharge', 'sms', { group: '系统' }, 'orgMgmt', 'members', 'settings', 'reports', 'audit', { group: '工具' }, 'appDownload'],
  SE: [{ group: '业务' }, 'workbench', 'dispatch', 'platformSea', 'projects', 'cases', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'reconOut', 'billing', { group: '系统' }, 'members', 'audit', { group: '报表' }, 'reports', { group: '工具' }, 'appDownload'],
  PL: [{ group: '业务' }, 'workbench', 'projects', 'cases', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'billing', 'recharge', 'sms', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  PC: [{ group: '业务' }, 'workbench', 'cases', 'callLog', 'myLinks', { group: '项目' }, 'projects', { group: '能力' }, 'qc', 'legal', 'evidence', { group: '财务' }, 'reconIn', { group: '我的' }, 'myStats', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  VL: [{ group: '业务' }, 'workbench', 'providerSea', 'projects', 'qc', 'cases', { group: '财务' }, 'reconOut', 'coCommission', 'recharge', 'billing', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  CO: [{ group: '业务' }, 'workbench', 'myCases', 'providerSea', 'callLog', 'myLinks', { group: '能力' }, 'qc', { group: '我的' }, 'myStats', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
}

// 对账菜单标题按角色视角相对命名（对标原型 navLabel）：物业(PL/PC)=付佣、服务商(VL) reconOut=收佣、平台用本名。
export function navLabel(path: string, role: string): string {
  const base = PATH2LABEL[path]
  if (path === '/settlement' && (role === 'PL' || role === 'PC')) return '付佣对账'
  if (path === '/settlement-out' && role === 'VL') return '收佣对账'
  return base
}

// 该角色菜单内的真实路由集（去 null/去重）；供路由守卫做「仅可进本角色菜单页」判定。
export function allowedPaths(role: string): string[] {
  const nav = NAV_BY_ROLE[role]
  if (!nav) return Object.keys(PATH2LABEL)   // 未知角色兜底：放行全部（与菜单兜底一致）
  const out: string[] = []
  for (const it of nav) {
    if (typeof it === 'object') continue
    const p = KEY2PATH[it]
    if (p && !out.includes(p)) out.push(p)
  }
  return out
}

// 下钻详情页：从本角色菜单页可点进、但自身不是菜单项的详情路由。
// 仅放行「有 id 的详情」（/batches/12），不放行裸列表页（/batches = 撮合派单，平台专属）。
//   PL/PC/VL 的「案件管理」是批次优先入口（CasesView.viewBatch → /batches/{id}），
//   且 PL 需在批次详情提案收佣比例（PUT /batches/{id}/comm-in-rate，三层佣金第一步）。
//   缺此放行会让菜单内的合法链接被守卫弹回 /dashboard。
// CO 不列入：其案件入口是私海/公海扁平清单，且资金双线上 CO 两线比例均不可见。
const DRILLDOWN_PATHS: Record<string, string[]> = {
  PL: ['/batches'], PC: ['/batches'], VL: ['/batches'],
}

/** 路由守卫判定单一入口：通用页 / 本角色菜单页(含子路径) / 本角色可下钻的详情页。 */
export function isAllowedPath(role: string, path: string, universalPaths: string[]): boolean {
  if (universalPaths.includes(path)) return true
  if (allowedPaths(role).some((p) => path === p || path.startsWith(p + '/'))) return true
  return (DRILLDOWN_PATHS[role] ?? []).some((p) => path.startsWith(p + '/'))
}
