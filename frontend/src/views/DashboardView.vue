<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'
import { caseStatusLabel, todoCategoryLabel } from '../constants/enums'
import CaseThreeColumn from '../components/CaseThreeColumn.vue'

// 角色工作台(GET /workbench · BR-M4-20/20a)：CO/PC=今日驾驶舱(待办列表+KPI可点筛)；管理角色=仪表盘。
const auth = useAuth()
const router = useRouter()
const me = computed(() => auth.me)
const wb = ref<any>(null)
const filterKey = ref<string>('')   // KPI 点击过滤（cockpit 专用）

const CAT_LABEL: Record<string, string> = {
  PROMISE_DUE: '承诺到期', RELEASE_WARN: '临近释放', TICKET_RECEIPT: '工单回执',
  NEW_ASSIGNED: '新分配', LEGAL_DELIVERY: '法务待送达', REPAY_MARK: '回款待标',
  PAYLINK_SEND: '链接待发', REDUCE_APPROVE: '减免待批',
  T2_RETURN_WARN: '即将退回平台', T1_DISPATCH_WARN: '待派单超时',
}
const urgType = (u: string) => (u === 'HIGH' ? 'danger' : u === 'MED' ? 'warning' : 'info')
// 纯展示：紧急度 → ds-admin 配色级别（色条/标签/ck-chip 复用）
const urgLv = (u: string) => (u === 'HIGH' ? 'dan' : u === 'MED' ? 'war' : 'inf')
const urgTag = (u: string) => (u === 'HIGH' ? 'dan' : u === 'MED' ? 'war' : 'inf')
const todos = computed<any[]>(() => {
  const list = wb.value?.todos ?? []
  return filterKey.value ? list.filter((t: any) => t.category === filterKey.value) : list
})

async function load() {
  const { data } = await api.GET('/workbench', {})
  wb.value = data
}
function openTodo(t: any) { if (t.caseId) router.push(`/cases/${t.caseId}`) }
onMounted(load)

// ===== cockpit master-detail（选中案件→内嵌 CaseThreeColumn，对标原型 <case-three-col> 复用）=====
// 选中态：本地 ref；与 KPI 筛选(filterKey)、tab 解耦。案件详情本身由 CaseThreeColumn 内部拉取，
// 这里只留头部条需要的极简信息（姓名/房号/状态），经 @loaded 事件从子组件拿，避免重复请求。
const cockpitId = ref<string>('')
const cpHeaderCase = ref<any>(null)
function onCaseLoaded(detail: any) { cpHeaderCase.value = detail?.case ?? null }
const caseStatusTag = (s?: string) => {
  const m: Record<string, string> = {
    SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
    PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
    WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
  }
  return m[s ?? ''] ?? 'inf'
}

// 左 worklist：复用 filterKey 过滤后的 todos（todo 字段不足时优雅降级）
const worklist = computed<any[]>(() => todos.value)
// 概览：紧急（HIGH）提醒，取全量 todos（不受 filterKey 限制）
const allTodos = computed<any[]>(() => wb.value?.todos ?? [])
const worklistUrgent = computed<any[]>(() => allTodos.value.filter((t: any) => t.urgency === 'HIGH').slice(0, 6))

// wl-tabs：由 todos 的 category 分布派生 + 按角色预置全部分类（即使计数为 0 也展示）
const CO_TODO_CATS = ['PROMISE_DUE', 'RELEASE_WARN', 'TICKET_RECEIPT']
const PC_TODO_CATS = ['TICKET_RECEIPT', 'LEGAL_DELIVERY', 'REPAY_MARK', 'PAYLINK_SEND']
const roleTodoCats = computed<string[]>(() => {
  const r = auth.me?.role
  if (r === 'CO') return CO_TODO_CATS
  if (r === 'PC') return PC_TODO_CATS
  return []
})
const wlTabs = computed<Array<{ k: string; l: string; n: number }>>(() => {
  const list = allTodos.value
  const counts: Record<string, number> = {}
  for (const t of list) counts[t.category] = (counts[t.category] || 0) + 1
  const tabs: Array<{ k: string; l: string; n: number }> = [{ k: '', l: '全部', n: list.length }]
  // 先展示预设分类（即使计数为 0），再补真实数据中出现的额外分类
  for (const cat of roleTodoCats.value) tabs.push({ k: cat, l: CAT_LABEL[cat] || cat, n: counts[cat] || 0 })
  for (const cat of Object.keys(counts)) {
    if (!roleTodoCats.value.includes(cat)) tabs.push({ k: cat, l: CAT_LABEL[cat] || cat, n: counts[cat] })
  }
  return tabs
})

// 今日概览进度（演示态：已选/进入过的视为已处理参考；基于 todos 总量给出剩余）
const ovTotal = computed<number>(() => allTodos.value.length || 0)
const ovRemain = computed<number>(() => allTodos.value.length)
const ovPct = computed<number>(() => 0) // 无「已处理」数据来源，进度条以剩余件数为主语义（保守置 0）

// 选中 todo 标题（案件详情未加载完成前的降级展示）
const cpTitle = computed<string>(() => {
  const t = (allTodos.value.find((x: any) => x.caseId === cockpitId.value))
  return t?.title || cockpitId.value
})

function selectWl(caseId?: string) {
  if (!caseId) return
  if (cockpitId.value !== caseId) cpHeaderCase.value = null
  cockpitId.value = caseId
}
function fullScreen() { if (cockpitId.value) router.push(`/cases/${cockpitId.value}`) }
// 完成→下一条：按当前筛选后的 worklist 顺序推进到下一个待办案件；处理完最后一件则清空选中并提示（对标原型 doneNext）
function doneNext() {
  const list = worklist.value.filter((t: any) => t.caseId)
  const i = list.findIndex((t: any) => t.caseId === cockpitId.value)
  const next = list[i + 1] || list.find((t: any) => t.caseId !== cockpitId.value)
  if (next && next.caseId !== cockpitId.value) {
    selectWl(next.caseId)
  } else {
    cockpitId.value = ''
    cpHeaderCase.value = null
    ElMessage.success('本批今日必办已处理完 🎉')
  }
}

// ========================================================================
//  Dashboard（管理角色 PL/SA/SE/VL）：经营仪表盘
//  对照原型 index.html lines 114-187 的 v-else 分支
// ========================================================================

// 时段切换
const wbPeriod = ref<'今日' | '本月' | '自定义'>('本月')
const wbFrom = ref('')
const wbTo = ref('')
const trendMode = ref<'按天' | '按月'>('按天')

// SVG 图标路径（ds-admin stroke-based，来自原型 ic map）
const icPaths: Record<string, string> = {
  money: 'M12 1v22M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6',
  check: 'M9 11l3 3L20 6M20 12v7a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h9',
  mine: 'M12 12a4 4 0 100-8 4 4 0 000 8M4 20a8 8 0 0116 0',
  wallet: 'M3 7h18v12H3zM16 12h3M3 7l3-3h12l3 3',
  stamp: 'M9 8a3 3 0 116 0c0 2-2 3-2 5h-2c0-2-2-3-2-5zM5 21h14M7 17h10v4H7z',
  sms: 'M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z',
  book: 'M5 4a1 1 0 011-1h13v18H6a1 1 0 01-1-1zM9 7h7M9 11h7',
  clock: 'M12 7v5l3 2M12 21a9 9 0 110-18 9 9 0 010 18',
  sea: 'M4 7h16v12H4zM4 11h16',
  cases: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01',
  member: 'M9 11a4 4 0 100-8 4 4 0 000 8M2 21a7 7 0 0114 0M18 8l2 2 3-3',
  batch: 'M4 8h16v12H4zM4 8l2-4h12l2 4M9 13h6',
  dispatch: 'M3 11l18-8-8 18-2-7-8-3z',
  building: 'M4 21V6l8-3 8 3v15M4 21h16M9 9h.01M9 13h.01M15 9h.01M15 13h.01',
  alert: 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z',
  phone: 'M22 16.9v3a2 2 0 01-2.2 2A19.8 19.8 0 013 5.1 2 2 0 012.2-2.2h3a2 2 0 012 1.7c.1.9.3 1.8.7 2.7a2 2 0 01-.5 2.1L9 10.9a16 16 0 006 6l1.4-1.4a2 2 0 012.1-.4c.9.3 1.8.5 2.7.6a2 2 0 011.7 2z',
  chart: 'M3 3v18h18M7 14l3-3 3 3 5-6',
  plus: 'M12 5v14M5 12h14',
  send: 'M22 2 11 13M22 2 15 22l-4-9-9-4z',
  scale: 'M3 6h18M6 6l3 12h6l3-12M12 6v12',
  coin: 'M12 2a10 10 0 100 20 10 10 0 000-20zM15 8H9v8h6M9 12h6',
  briefcase: 'M4 7a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2zM8 7V5a2 2 0 012-2h4a2 2 0 012 2v2',
}

// KPI label → icon/color/trend 映射（回退关键词匹配）
function kpiMeta(label: string): { i: string; c: string; t: string; tx: string } {
  const exact: Record<string, { i: string; c: string; t: string; tx: string }> = {
    '回款金额': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 12%' },
    '全平台回款': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 15%' },
    '本月回款': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 12%' },
    '回款案件数': { i: 'check', c: '#2563EB', t: 'up', tx: '▲ 8' },
    '在催案件': { i: 'cases', c: '#3b82f6', t: 'up', tx: '▲ 3' },
    '本月回款(元)': { i: 'money', c: '#10b981', t: 'up', tx: '▲ 12%' },
    '回款率': { i: 'chart', c: '#f59e0b', t: 'up', tx: '▲ 5%' },
    '待跟进': { i: 'clock', c: '#ef4444', t: 'down', tx: '▼ 2' },
    '本月通话': { i: 'phone', c: '#8b5cf6', t: 'up', tx: '▲ 8%' },
    '新增案件': { i: 'plus', c: '#06b6d4', t: 'up', tx: '▲ 15' },
    '待派单': { i: 'send', c: '#f97316', t: 'up', tx: '▲ 1' },
    '已结清': { i: 'check', c: '#84cc16', t: 'up', tx: '▲ 9' },
    '法务在办': { i: 'scale', c: '#a855f7', t: 'flat', tx: '—' },
    '违约金收入': { i: 'coin', c: '#eab308', t: 'up', tx: '▲ 3%' },
    '待办合计': { i: 'clock', c: '#f59e0b', t: 'flat', tx: '—' },
    '待派超时': { i: 'send', c: '#ef4444', t: 'up', tx: '需关注' },
  }
  if (exact[label]) return exact[label]
  // keyword fallback
  if (label.includes('回款') && label.includes('金额')) return { i: 'money', c: '#15A35B', t: 'up', tx: '—' }
  if (label.includes('回款') && label.includes('案')) return { i: 'check', c: '#2563EB', t: 'up', tx: '—' }
  if (label.includes('回款')) return { i: 'money', c: '#15A35B', t: 'up', tx: '—' }
  if (label.includes('案件') || label.includes('新增')) return { i: 'cases', c: '#2563EB', t: 'flat', tx: '—' }
  if (label.includes('通话') || label.includes('分钟')) return { i: 'wallet', c: '#E6A23C', t: 'flat', tx: '—' }
  if (label.includes('工单')) return { i: 'stamp', c: '#7C5CFC', t: 'flat', tx: '—' }
  if (label.includes('短信')) return { i: 'sms', c: '#11A8B5', t: 'flat', tx: '—' }
  if (label.includes('存证')) return { i: 'book', c: '#7C5CFC', t: 'flat', tx: '—' }
  if (label.includes('链接')) return { i: 'sea', c: '#11A8B5', t: 'flat', tx: '—' }
  if (label.includes('承诺')) return { i: 'clock', c: '#15A35B', t: 'flat', tx: '—' }
  if (label.includes('在催')) return { i: 'mine', c: '#11A8B5', t: 'flat', tx: '—' }
  if (label.includes('联系')) return { i: 'sms', c: '#2563EB', t: 'flat', tx: '—' }
  if (label.includes('派单')) return { i: 'dispatch', c: '#2563EB', t: 'flat', tx: '—' }
  if (label.includes('对账')) return { i: 'batch', c: '#11A8B5', t: 'flat', tx: '—' }
  if (label.includes('全平台') || label.includes('平台')) return { i: 'money', c: '#15A35B', t: 'flat', tx: '—' }
  if (label.includes('公海')) return { i: 'sea', c: '#E6A23C', t: 'flat', tx: '—' }
  if (label.includes('服务商')) return { i: 'member', c: '#11A8B5', t: 'flat', tx: '—' }
  if (label.includes('物业')) return { i: 'building', c: '#11A8B5', t: 'flat', tx: '—' }
  return { i: 'mine', c: '#2563EB', t: 'flat', tx: '—' }
}

// 经营 KPI 卡片（API wb.kpis 驱动，演示环境下补足 10+ 项）
const DEMO_KPIS = [
  { l: '在催案件', n: 28, i: 'briefcase', c: '#3b82f6', t: '↑3', tx: '较上月' },
  { l: '本月回款(元)', n: '82,500', i: 'money', c: '#10b981', t: '↑12%', tx: '较上月' },
  { l: '回款率', n: '62.3%', i: 'chart', c: '#f59e0b', t: '↑5%', tx: '较上月' },
  { l: '待跟进', n: 15, i: 'clock', c: '#ef4444', t: '↓2', tx: '较上周' },
  { l: '本月通话', n: 186, i: 'phone', c: '#8b5cf6', t: '↑8%', tx: '较上月' },
  { l: '新增案件', n: 42, i: 'plus', c: '#06b6d4', t: '↑15', tx: '本月' },
  { l: '待派单', n: 5, i: 'send', c: '#f97316', t: '↑1', tx: '需关注' },
  { l: '已结清', n: 134, i: 'check', c: '#84cc16', t: '↑9', tx: '本月' },
  { l: '法务在办', n: 3, i: 'scale', c: '#a855f7', t: '—', tx: '持平' },
  { l: '违约金收入', n: '12,400', i: 'coin', c: '#eab308', t: '↑3%', tx: '本月' },
]
const dashboardKpis = computed(() => {
  const apiKpis = (wb.value?.kpis ?? []).map((k: any) => {
    const meta = kpiMeta(k.label)
    return { l: k.label, n: k.value, i: meta.i, c: meta.c, t: meta.t, tx: meta.tx }
  })
  // 演示环境：API 返回不足时补 Demo 数据
  if (apiKpis.length < 3) return [...apiKpis, ...DEMO_KPIS]
  return apiKpis
})

// 回款趋势（演示态静态数据，API 暂未提供趋势端点）
const TREND_DAY = [
  { label: '06-25', v: 8, amt: '¥0.8万', n: 6 },
  { label: '06-26', v: 12, amt: '¥1.2万', n: 9 },
  { label: '06-27', v: 6, amt: '¥0.6万', n: 4 },
  { label: '06-28', v: 15, amt: '¥1.5万', n: 11 },
  { label: '06-29', v: 10, amt: '¥1.0万', n: 8 },
  { label: '06-30', v: 18, amt: '¥1.8万', n: 13 },
  { label: '07-01', v: 9, amt: '¥0.9万', n: 7 },
]
const TREND_MONTH = [
  { label: '1月', v: 40, amt: '¥4.0万', n: 32 },
  { label: '2月', v: 55, amt: '¥5.5万', n: 45 },
  { label: '3月', v: 48, amt: '¥4.8万', n: 39 },
  { label: '4月', v: 62, amt: '¥6.2万', n: 51 },
  { label: '5月', v: 82, amt: '¥8.2万', n: 66 },
  { label: '6月', v: 96, amt: '¥9.6万', n: 79 },
]
const repayTrend = computed(() => {
  const arr = trendMode.value === '按月' ? TREND_MONTH : TREND_DAY
  const max = Math.max(...arr.map((t) => t.v)) || 1
  return arr.map((t) => ({ label: t.label, amt: t.amt, n: t.n, h: Math.round((t.v / max) * 100) }))
})
const repayTrendSum = computed(() => (trendMode.value === '按月' ? '¥38.3万' : '¥7.8万'))
const repayTrendCnt = computed(() => repayTrend.value.reduce((a: number, t) => a + t.n, 0))

// 今日看板 todos（API wb.todos 驱动，映射为 今日看板 展示格式）
const dashboardTodos = computed(() => {
  return (wb.value?.todos ?? []).map((t: any) => {
    const lv = urgLv(t.urgency)
    return {
      t: t.title,
      n: 1,
      lv,
      tg: lv === 'dan' ? '紧急' : lv === 'war' ? '重要' : lv === 'inf' ? '通知' : '一般',
      jump: t.caseId ? '' : (t.refType ?? ''),
      caseId: t.caseId,
    }
  })
})

// 数据范围标签（经营概览卡片用）
const scopeLabel = computed(() => {
  const ot = me.value?.org?.type
  if (ot === 'PROPERTY') return '本物业'
  if (ot === 'PROVIDER') return '本服务商'
  if (me.value?.dataScope) return '数据范围内'
  return '全平台'
})

// PL 协调员督导概览（演示态静态数据，API 暂未提供）
const superviseRows = computed(() => {
  if (me.value?.role !== 'PL') return []
  return [
    { name: '钱协调', acct: 'pc_qian', handled: 42, tickets: 12, evidence: 7, resp: '2.1h', risk: 1, overdue: 2 },
    { name: '孙协调', acct: 'pc_sun', handled: 30, tickets: 8, evidence: 3, resp: '3.4h', risk: 0, overdue: 0 },
  ]
})

// 今日看板 → 跳转
function todoJump(td: any) {
  if (td.caseId) { router.push(`/cases/${td.caseId}`); return }
  if (td.jump === 'supervise') { router.push('/members'); return }
  if (td.jump === 'projects') { router.push('/projects'); return }
  if (td.jump === 'reconIn') { router.push('/recon-in'); return }
  if (td.jump === 'qc') { router.push('/qc'); return }
  if (td.jump === 'cases') { router.push('/cases'); return }
  // generic fallback: try refType as route path
  if (td.jump && typeof td.jump === 'string' && td.jump !== '') {
    router.push(`/${td.jump}`).catch(() => {})
  }
}

// ===== CO cockpit KPI 渲染辅助 =====
// 金额类 KPI 格式化（分→元）
function chipValue(k: any): string {
  const label = String(k.label || '')
  if (label.includes('回款')) {
    const yuan = (k.value || 0) / 100
    return '¥' + yuan.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
  }
  return String(k.value ?? '—')
}
// KPI 颜色/状态映射（原型 cockpitChips 配色）
function chipClass(k: any): string {
  const label = String(k.label || '')
  if (label.includes('待跟进') || label.includes('待办合计')) return 'war'
  if (label.includes('今日通话') || label.includes('本月回款')) return 'suc'
  if (label.includes('持有案件')) return 'pri'
  if (label.includes('承诺到期')) return 'dan'
  if (label.includes('临近释放')) return 'dan'
  if (label.includes('临近退回')) return 'dan'
  if (label.includes('工单回执')) return 'inf'
  if (label.includes('待派超时')) return 'dan'
  return 'inf'
}

// ===== 驾驶舱 KPI 可点筛选条（对标原型 COCKPIT_CHIPS：按角色固定 6 项，含 0 计数亦展示）=====
// 可点项(key=category / '')点击即过滤 worklist；只读项(ro)展示统计值（值取自后端 wb.kpis）。
type ChipDef = { key: string; label: string; cls: string; ro?: boolean; kpiLabel?: string }
const COCKPIT_CHIP_TPL: Record<string, ChipDef[]> = {
  CO: [
    { key: '', label: '全部待办', cls: 'pri' },
    { key: 'PROMISE_DUE', label: '承诺到期', cls: 'dan' },
    { key: 'RELEASE_WARN', label: '临近释放', cls: 'dan' },
    { key: 'TICKET_RECEIPT', label: '工单待回', cls: 'war' },
    { key: '_call', label: '今日通话', cls: 'inf', ro: true, kpiLabel: '今日通话' },
    { key: '_repay', label: '本月回款', cls: 'suc', ro: true, kpiLabel: '本月回款' },
  ],
  PC: [
    { key: '', label: '全部待办', cls: 'pri' },
    { key: 'LEGAL_DELIVERY', label: '法务待送达', cls: 'dan' },
    { key: 'TICKET_RECEIPT', label: '工单待回', cls: 'war' },
    { key: 'REDUCE_APPROVE', label: '减免·线下', cls: 'war' },
    { key: 'REPAY_MARK', label: '回款待标', cls: 'pri' },
    { key: '_repay', label: '本月回款', cls: 'suc', ro: true, kpiLabel: '本月回款' },
  ],
}
const cockpitChips = computed<Array<ChipDef & { n: string | number }>>(() => {
  const tpl = COCKPIT_CHIP_TPL[auth.me?.role ?? ''] ?? []
  const counts: Record<string, number> = {}
  for (const t of allTodos.value) counts[t.category] = (counts[t.category] || 0) + 1
  const kpiByLabel: Record<string, any> = {}
  for (const k of (wb.value?.kpis ?? [])) kpiByLabel[k.label] = k
  return tpl.map((c) => {
    if (c.ro) {
      const kpi = kpiByLabel[c.kpiLabel ?? '']
      const n = kpi == null ? '—'
        : String(c.kpiLabel).includes('回款') ? '¥' + ((kpi.value || 0) / 100).toLocaleString('zh-CN')
          : String(kpi.value)
      return { ...c, n }
    }
    return { ...c, n: c.key === '' ? allTodos.value.length : (counts[c.key] || 0) }
  })
})
</script>

<template>
  <div v-if="me">
    <!-- ================================================================ -->
    <!--  Cockpit：一线办案角色（CO/PC）· 今日驾驶舱                          -->
    <!-- ================================================================ -->
    <template v-if="wb?.layout === 'cockpit'">
      <!-- ① KPI 可点即筛选条（BR-M4-20a · 驾驶舱 ck-chip · 对标原型 COCKPIT_CHIPS 固定 6 项） -->
      <div v-if="cockpitChips.length" class="cockpit-kpis">
        <div
          v-for="c in cockpitChips"
          :key="c.label"
          class="ck-chip"
          :class="[c.cls, { on: !c.ro && filterKey === c.key, ro: c.ro }]"
          @click="!c.ro && (filterKey = filterKey === c.key ? '' : c.key)"
        >
          <div class="n">{{ c.n }}</div>
          <div class="l">{{ c.label }}</div>
        </div>
      </div>

      <!-- ② 今日驾驶舱 master-detail = 左今日必办 worklist + 右选中案件预览 -->
      <div class="cockpit">
        <!-- 左：今日必办 worklist（tab 过滤 + 紧急度色条） -->
        <div class="wl">
          <div class="wl-tabs">
            <span
              v-for="t in wlTabs"
              :key="t.k"
              class="wl-tab"
              :class="{ on: filterKey === t.k }"
              @click="filterKey = t.k"
            >{{ t.l }}<b>{{ t.n }}</b></span>
          </div>
          <div class="wl-list">
            <div
              v-for="(it, i) in worklist"
              :key="it.caseId || i"
              class="wl-item"
              :class="[urgLv(it.urgency), { on: cockpitId && cockpitId === it.caseId }]"
              @click="it.caseId ? selectWl(it.caseId) : openTodo(it)"
            >
              <div class="bar2"></div>
              <div class="wl-main">
                <div class="r1">
                  <span class="nm">{{ it.title }}</span>
                  <span v-if="it.deadline" class="amt" style="font-weight:400;color:var(--sec);font-size:12px">
                    {{ String(it.deadline).slice(0, 16).replace('T', ' ') }}
                  </span>
                </div>
                <div class="r2">
                  <span class="tag" :class="urgTag(it.urgency)">{{ CAT_LABEL[it.category] || it.category }}</span>
                  <span class="sla" :class="urgLv(it.urgency)">
                    {{ it.caseId ? '预览 →' : '无关联案件' }}
                  </span>
                </div>
              </div>
            </div>
            <div v-if="!worklist.length" class="wl-empty">该分类下暂无待办 🎉</div>
          </div>
        </div>

        <!-- 右：选中→内嵌完整三栏案件详情（缩小版，对标原型 <case-three-col>）；未选→今日概览 -->
        <div class="cp-detail" :class="{ embed: cockpitId }">
          <!-- 已选：内嵌三栏，与 /cases/:id 整页同一组件，仅头部条 + 完成下一条/全屏 是工作台专属外壳 -->
          <div v-if="cockpitId" class="cp-embed">
            <div class="cp-embed-h">
              <div class="t">
                {{ cpHeaderCase?.ownerName || cpTitle }}
                <template v-if="cpHeaderCase?.room"> · {{ cpHeaderCase.room }}</template>
                <span v-if="cpHeaderCase?.status" class="tag" :class="caseStatusTag(cpHeaderCase.status)" :title="cpHeaderCase.status">{{ caseStatusLabel(cpHeaderCase.status) }}</span>
              </div>
              <div class="ops">
                <button class="btn df sm" @click="doneNext()">完成 · 下一条 ↓</button>
                <button class="btn pl sm" @click="fullScreen()">全屏 ⤢</button>
              </div>
            </div>
            <CaseThreeColumn :case-id="cockpitId" @loaded="onCaseLoaded" />
          </div>

          <!-- 未选：今日概览 -->
          <div v-else class="cp-overview">
            <div class="ov-h">今日概览 · {{ me.name }}</div>
            <div class="ov-progress">
              <div class="ov-bar"><div class="ov-fill" :style="{ width: ovPct + '%' }"></div></div>
              <div class="ov-txt">今日待办 <b>{{ ovTotal }}</b> 件 · 剩余 {{ ovRemain }} 件待处理</div>
            </div>
            <div class="sec-title">重点提醒（紧急）</div>
            <div class="tl">
              <div class="e" v-for="it in worklistUrgent" :key="it.caseId || it.title" @click="selectWl(it.caseId)">
                <span class="tag" :class="urgTag(it.urgency)">{{ CAT_LABEL[it.category] || it.category }}</span>
                {{ it.title }}
                <b v-if="it.deadline" style="float:right">{{ String(it.deadline).slice(0, 16).replace('T', ' ') }}</b>
              </div>
              <div v-if="!worklistUrgent.length" class="note">今日紧急事项已清空 ✅</div>
            </div>
            <div class="note" style="margin-top:14px">← 从左侧「今日必办」选一个案子开始处理</div>
          </div>
        </div>
      </div>
    </template>

    <!-- ================================================================ -->
    <!--  Dashboard：管理角色（PL/SA/SE/VL）· 经营仪表盘                      -->
    <!--  对照原型 index.html lines 114-187                                -->
    <!-- ================================================================ -->
    <template v-else-if="wb?.layout === 'dashboard'">
      <!-- 时段切换卡片 -->
      <div class="card" style="margin-bottom:12px;padding:10px 14px">
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
          <span style="font-weight:600">经营概览</span>
          <span class="segctrl">
            <span :class="{ on: wbPeriod === '今日' }" @click="wbPeriod = '今日'">今日</span>
            <span :class="{ on: wbPeriod === '本月' }" @click="wbPeriod = '本月'">本月</span>
            <span :class="{ on: wbPeriod === '自定义' }" @click="wbPeriod = '自定义'">自定义</span>
          </span>
          <template v-if="wbPeriod === '自定义'">
            <input class="inp" type="date" v-model="wbFrom" aria-label="起始" style="min-width:140px">
            <span class="note" style="margin:0">~</span>
            <input class="inp" type="date" v-model="wbTo" aria-label="结束" style="min-width:140px">
          </template>
          <span class="note" style="margin:0;margin-left:auto">{{ scopeLabel }} · 指标按所选时段统计</span>
        </div>
      </div>

      <!-- KPI 指标卡片 -->
      <div v-if="dashboardKpis.length" class="kpis">
        <div class="kpi" v-for="s in dashboardKpis" :key="s.l">
          <div class="ic" :style="{ background: s.c }">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path :d="icPaths[s.i] || icPaths.mine" />
            </svg>
          </div>
          <div class="n">{{ s.n }}</div>
          <div class="l">{{ s.l }}</div>
          <div class="tr" :class="s.t">{{ s.tx }}</div>
        </div>
      </div>

      <!-- 今日看板 + 回款趋势（双栏） -->
      <div class="grid2">
        <!-- 今日看板 -->
        <div class="card">
          <div class="card-h">
            <div class="t"><span class="bar"></span>今日看板 — {{ me.name }} · 当前应办</div>
            <div class="ops"><span class="note" style="margin:0">点条目直达</span></div>
          </div>
          <div class="tl" v-if="dashboardTodos.length">
            <div
              class="e clickable"
              v-for="td in dashboardTodos"
              :key="td.t + (td.caseId || '')"
              @click="todoJump(td)"
            >
              <span class="tag" :class="td.lv">{{ td.tg }}</span>
              {{ td.t }}
              <b style="float:right">{{ td.n }}</b>
              <span class="td-arr">›</span>
            </div>
          </div>
          <div v-else class="note" style="padding:20px 0;text-align:center">今日暂无应办事项 ✅</div>
        </div>

        <!-- 回款趋势 -->
        <div class="card">
          <div class="card-h">
            <div class="t"><span class="bar"></span>回款趋势（{{ scopeLabel }}）</div>
            <div class="ops">
              <span class="segctrl">
                <span :class="{ on: trendMode === '按天' }" @click="trendMode = '按天'">按天</span>
                <span :class="{ on: trendMode === '按月' }" @click="trendMode = '按月'">按月</span>
              </span>
            </div>
          </div>
          <div style="display:flex;align-items:flex-end;gap:8px;height:150px;padding:8px 4px 0">
            <div
              v-for="t in repayTrend"
              :key="t.label"
              style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:100%"
            >
              <div style="font-size:11px;color:var(--sec)">{{ t.amt }}</div>
              <div
                :style="{
                  width: '62%',
                  background: 'var(--primary)',
                  borderRadius: '4px 4px 0 0',
                  height: t.h + '%',
                  minHeight: '2px',
                }"
                :title="t.label + ' ' + t.amt + '·' + t.n + '件'"
              ></div>
              <div class="note" style="margin-top:4px;font-size:11px">{{ t.label }}</div>
            </div>
          </div>
          <div class="note" style="margin-top:6px">
            {{ trendMode }}回款（演示态）；本期合计 <b>{{ repayTrendSum }}</b> · 回款案件 <b>{{ repayTrendCnt }}</b> 件。
          </div>
        </div>
      </div>

      <!-- PL 协调员督导概览（原型 lines 171-187） -->
      <template v-if="me.role === 'PL' && superviseRows.length">
        <div class="card" style="margin-top:16px">
          <div class="card-h">
            <div class="t"><span class="bar"></span>协调员督导概览（本月）</div>
            <div class="ops">
              <button class="btn txt" @click="router.push('/members')">去工作督导 ›</button>
            </div>
          </div>
          <table>
            <thead>
              <tr>
                <th>协调员</th><th>处理案件</th><th>工单</th><th>存证</th><th>平均响应</th><th>质检风险</th><th>超时未处理</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="m in superviseRows"
                :key="m.acct"
                :style="(m.overdue || m.risk) ? 'background:#fff7f5' : undefined"
              >
                <td>
                  {{ m.name }}
                  <span v-if="m.overdue || m.risk" class="tag dan" style="font-size:11px">异常</span>
                </td>
                <td class="num">{{ m.handled }}</td>
                <td class="num">{{ m.tickets }}</td>
                <td class="num">{{ m.evidence }}</td>
                <td>{{ m.resp }}</td>
                <td>
                  <span v-if="m.risk" class="tag dan">{{ m.risk }}</span>
                  <span v-else class="tag suc">0</span>
                </td>
                <td>
                  <span v-if="m.overdue" class="tag dan">{{ m.overdue }}</span>
                  <span v-else>—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="note">
            超时未处理 / 质检风险高的协调员标"异常"；点「去工作督导」发起 提醒/谈话/培训（演示态静态）。
          </div>
        </div>
      </template>
    </template>

    <!-- 加载中占位（wb 尚未返回） -->
    <div v-else class="note" style="text-align:center;padding:48px 0">加载工作台数据中…</div>
  </div>
  <div v-else class="note" style="text-align:center;padding:48px 0">加载主体中…</div>
</template>
