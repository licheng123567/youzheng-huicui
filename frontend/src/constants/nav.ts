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
  // v1.19.0：计费明细+充值中心合并为「额度管理」（组织维度余额+用量+充值）；短信通道独立
  quota: '/quota', sms: '/sms',
  members: '/members', orgMgmt: '/org-mgmt', settings: '/settings', playbookLib: '/script-lib',
  reports: '/reports', audit: '/audit-log', inbox: '/notifications',
  legal: '/legal', myStats: '/my-stats', myLinks: '/my-links',
  // 外勤作业 App 下载与使用说明：菜单对所有角色开放（管理角色需要把安装链接转发给手下作业人员），
  // 但 App 本身只对催收员(CO)与物业协调员(PC)开放（BR-APP-01），页面内文案按角色分叉。
  appDownload: '/app-download',
}

export const PATH2LABEL: Record<string, string> = {
  // v1.17.0 三合一：/batches 对平台=「案件运营」（批次运营+派单/结项+平台公海 Tab）；
  // dispatch key 仅 SA/SE 菜单持有，PL/PC/VL 只下钻 /batches/{id} 详情不显示此标签，全局改名安全。
  '/dashboard': '工作台', '/batches': '案件运营', '/sea': '案件公海', '/projects': '项目管理',
  '/cases': '案件管理', '/call-records': '通话记录', '/risks': '质检/风控', '/evidence': '存证管理',
  '/settlement': '收佣对账', '/settlement-out': '付佣对账', '/co-commission': '催收员佣金',
  '/quota': '额度管理', '/sms': '短信通道',
  '/members': '成员管理', '/org-mgmt': '组织管理', '/settings': '参数配置', '/script-lib': '平台话术库',
  '/reports': '经营报表', '/audit-log': '操作日志', '/notifications': '消息中心',
  '/legal': '送达管理', '/my-stats': '我的业绩', '/my-links': '缴费链接',
  '/app-download': '外勤作业 App',
}

export const NAV_BY_ROLE: Record<string, NavItem[]> = {
  // 平台侧（SA/SE）财务只留一条「结算对账」（reconIn=/settlement 平台双线总账 v1.16.0）：
  // 收佣/付佣两菜单曾同指 SettlementView 仅差 side——对平台是同一屏，合并；PL/PC/VL 各自单线菜单不受影响。
  // v1.17.0 三合一：平台侧去掉独立「案件管理」菜单（cases）——案件运营(/batches)一站式承载
  // 批次运营+派单/结项+平台公海 Tab，案件明细走批次下钻；老书签 /cases、/sea 由 ROLE_REDIRECTS 转 /batches。
  SA: [{ group: '业务' }, 'workbench', 'dispatch', 'projects', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'quota', 'sms', { group: '系统' }, 'orgMgmt', 'members', 'settings', 'reports', 'audit', { group: '工具' }, 'appDownload'],
  SE: [{ group: '业务' }, 'workbench', 'dispatch', 'projects', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'quota', 'sms', { group: '系统' }, 'members', 'audit', { group: '报表' }, 'reports', { group: '工具' }, 'appDownload'],
  PL: [{ group: '业务' }, 'workbench', 'projects', 'cases', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'quota', 'sms', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  PC: [{ group: '业务' }, 'workbench', 'cases', 'callLog', 'myLinks', { group: '项目' }, 'projects', { group: '能力' }, 'qc', 'legal', 'evidence', { group: '财务' }, 'reconIn', { group: '我的' }, 'myStats', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  VL: [{ group: '业务' }, 'workbench', 'providerSea', 'projects', 'qc', 'cases', { group: '财务' }, 'reconOut', 'coCommission', 'quota', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
  CO: [{ group: '业务' }, 'workbench', 'myCases', 'providerSea', 'callLog', 'myLinks', { group: '能力' }, 'qc', { group: '我的' }, 'myStats', { group: '消息' }, 'inbox', { group: '工具' }, 'appDownload'],
}

// 对账菜单标题按角色视角相对命名（对标原型 navLabel）：物业(PL/PC)=付佣、服务商(VL) reconOut=收佣、
// 平台(SA/SE)=「结算对账」（v1.16.0 双线总账单菜单）。
export function navLabel(path: string, role: string): string {
  const base = PATH2LABEL[path]
  if (path === '/settlement' && (role === 'PL' || role === 'PC')) return '付佣对账'
  if (path === '/settlement' && (role === 'SA' || role === 'SE')) return '结算对账'
  if (path === '/settlement-out' && role === 'VL') return '收佣对账'
  // 公海收权后两侧看到的池不同（BR-M3-29）。平台侧菜单叫「平台公海」；
  // 服务商侧沿用原型的「案件公海」（页内含 待接单/服务商公海/开放抢单池 三个 tab，
  // 叫"服务商公海"会跟页内的同名 tab 打架——菜单是入口，tab 才是池）。
  if (path === '/sea') return role === 'SA' || role === 'SE' ? '平台公海' : '案件公海'
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
  // v1.17.0：平台 /cases 裸列表被 redirect 到 /batches，但 /cases/{id} 案件详情、
  // /cases/{id}/call/{callId} 通话详情仍是批次下钻链路的合法落点（redirectPath 精确匹配不含子路径）。
  SA: ['/cases'], SE: ['/cases'],
}

// 角色级老书签重定向（v1.16.0）：平台(SA/SE)的 /settlement-out 已并入 /settlement 双线总账。
// 守卫在越权判定前先查此表——命中即 redirect（非拦截回 dashboard）；e2e-nav-lint 同源消费。
const ROLE_REDIRECTS: Record<string, Record<string, string>> = {
  // v1.17.0 三合一：平台老书签 /cases、/sea 并入 /batches 案件运营（案件明细走批次下钻，公海是页内 Tab）。
  // v1.19.0：/billing、/recharge 并入 /quota 额度管理（有该菜单的四角色各自重定向）。
  //   **PC/CO 绝不加**：isAllowedPath 命中 redirect 即放行，加了他们就能进本无权限的页面。
  SA: { '/settlement-out': '/settlement', '/cases': '/batches', '/sea': '/batches', '/billing': '/quota', '/recharge': '/quota' },
  SE: { '/settlement-out': '/settlement', '/cases': '/batches', '/sea': '/batches', '/billing': '/quota', '/recharge': '/quota' },
  PL: { '/billing': '/quota', '/recharge': '/quota' },
  VL: { '/billing': '/quota', '/recharge': '/quota' },
}

/** 该角色访问 path 是否应重定向；返回目标路由或 null。router 守卫与 nav-lint 共用（SSOT）。 */
export function redirectPath(role: string, path: string): string | null {
  return ROLE_REDIRECTS[role]?.[path] ?? null
}

/** 路由守卫判定单一入口：通用页 / 本角色菜单页(含子路径) / 本角色可下钻的详情页 / 有角色级重定向的老书签。 */
export function isAllowedPath(role: string, path: string, universalPaths: string[]): boolean {
  if (universalPaths.includes(path)) return true
  if (redirectPath(role, path)) return true   // 重定向源路径不算越权（守卫会先转走）
  if (allowedPaths(role).some((p) => path === p || path.startsWith(p + '/'))) return true
  return (DRILLDOWN_PATHS[role] ?? []).some((p) => path.startsWith(p + '/'))
}
