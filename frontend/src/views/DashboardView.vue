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
onMounted(() => { load(); loadVlTeam() })

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

// KPI label → icon/color 映射（纯展示：图标与语义色）。
//
// **不再返回涨跌趋势**。这里曾经给每个 KPI 配一个写死的同比，比如
//   '本月回款': { tx: '▲ 12%' }、'待跟进': { tx: '▼ 2' }
// —— 数字是后端给的真值，可紧挨着它的「▲ 12% 较上月」是编的。
// 这比整块假数据更阴险：它寄生在真数据旁边，看起来完全可信。
// 契约里没有任何同比/环比端点，所以趋势位一律留空，直到真有数据。
function kpiMeta(label: string): { i: string; c: string; t: string; tx: string } {
  const flat = (i: string, c: string) => ({ i, c, t: 'flat', tx: '' })
  if (label.includes('回款') && label.includes('案')) return flat('check', '#2563EB')
  if (label.includes('回款')) return flat('money', '#15A35B')
  if (label.includes('结清')) return flat('check', '#84cc16')
  if (label.includes('待跟进') || label.includes('待办') || label.includes('承诺')) return flat('clock', '#E6A23C')
  if (label.includes('通话') || label.includes('分钟')) return flat('phone', '#8b5cf6')
  if (label.includes('案件') || label.includes('新增') || label.includes('在催')) return flat('cases', '#2563EB')
  if (label.includes('工单')) return flat('stamp', '#7C5CFC')
  if (label.includes('短信')) return flat('sms', '#11A8B5')
  if (label.includes('存证')) return flat('book', '#7C5CFC')
  if (label.includes('链接')) return flat('sea', '#11A8B5')
  if (label.includes('法务')) return flat('scale', '#a855f7')
  if (label.includes('派单') || label.includes('派超时')) return flat('dispatch', '#2563EB')
  if (label.includes('释放') || label.includes('退回')) return flat('alert', '#E5484D')
  if (label.includes('对账')) return flat('batch', '#11A8B5')
  if (label.includes('公海')) return flat('sea', '#E6A23C')
  if (label.includes('服务商')) return flat('member', '#11A8B5')
  if (label.includes('物业')) return flat('building', '#11A8B5')
  return flat('mine', '#2563EB')
}

// 经营 KPI 卡片：**只认后端**（GET /workbench 的 kpis）。
//
// 这里曾经有一组 DEMO_KPIS（"在催案件 28 / 本月回款 82,500 / 回款率 62.3% ↑12%"），
// 在 `apiKpis.length < 3` 时**追加**上去。而那个条件恰恰在**新租户、空数据**时成立 ——
// 也就是说，最该显示"暂无数据"的时刻，客户看到的是一整屏编造的经营业绩，还带着
// "较上月 ↑12%" 这种不可能存在的同比。这不是"待接 API"，是会让客户据此做决策的假数据。
// 空状态永远好过编造的财务数字。
const dashboardKpis = computed(() =>
  (wb.value?.kpis ?? []).map((k: any) => {
    const meta = kpiMeta(k.label)
    return { l: k.label, n: k.value, i: meta.i, c: meta.c, t: meta.t, tx: meta.tx }
  }),
)

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
// ── VL 团队即时看板（US-M10-03·原型 lines 150-168）──
// 数据源复用 GET /providers/{id}/collector-capacity（own-org·case.assign 门控，指派弹窗同款端点）：
// v1.8.0 起该端点带 todayActions/todayRepayCents，工作台不必再立新端点。
const vlTeam = ref<any[]>([])
const vlHoldCap = ref(0)
async function loadVlTeam() {
  const orgId = auth.me?.org?.id
  if (auth.me?.role !== 'VL' || !orgId) return
  const { data } = await api.GET('/providers/{id}/collector-capacity', { params: { path: { id: String(orgId) } } } as any)
  vlTeam.value = (data as any)?.items ?? []
  vlHoldCap.value = (data as any)?.holdCap ?? 0
}


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

      <!-- KPI 指标卡片：没有就显示空状态，绝不拿演示数据顶上（见 dashboardKpis 注释） -->
      <div v-if="!dashboardKpis.length" class="card">
        <div class="note" style="padding:24px 0;text-align:center">
          暂无经营数据 —— 导入批次并开始催收后，这里会显示真实指标。
        </div>
      </div>
      <div v-else class="kpis">
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

        <!--
          「回款趋势」图已删除：它 100% 是写死的（日期硬编码 06-25~07-01、金额 ¥38.3万），
          从不调任何 API —— 契约里也**没有**按时间聚合回款的端点。
          一张永远显示同一组假数字的趋势图，比没有这张图更糟。
          真要做，先在契约里加按日/按月聚合回款的端点（见 PR 说明的「契约缺口」）。
        -->
      </div>

      <!-- VL 团队即时看板（US-M10-03·原型 lines 150-168）：每个催收员的实时作业状态，仅 VL 工作台 -->
      <template v-if="me.role === 'VL'">
        <div class="card" style="margin-top:16px">
          <div class="card-h">
            <div class="t"><span class="bar"></span>团队即时看板（US-M10-03）</div>
            <div class="ops"><button class="btn txt sm" @click="loadVlTeam">刷新</button></div>
          </div>
          <table>
            <thead>
              <tr><th>催收员</th><th style="width:110px">持有案件数</th><th style="width:100px">今日动作</th><th style="width:110px">容量余量</th><th style="width:120px">今日回款</th><th style="width:90px">状态</th></tr>
            </thead>
            <tbody>
              <tr v-for="m in vlTeam" :key="m.collectorId">
                <td>{{ m.name }}</td>
                <td class="num">{{ m.holding }}</td>
                <td class="num">{{ m.todayActions ?? 0 }}</td>
                <td><span class="tag" :class="m.remaining <= 0 ? 'dan' : (m.remaining <= 5 ? 'war' : 'suc')">余{{ m.remaining }}件</span></td>
                <td class="num">{{ m.todayRepayCents != null ? '¥' + (m.todayRepayCents / 100).toLocaleString('zh-CN') : '—' }}</td>
                <td><span class="tag" :class="m.remaining <= 0 ? 'dan' : 'suc'">{{ m.remaining <= 0 ? '满员' : '在线' }}</span></td>
              </tr>
              <tr v-if="!vlTeam.length"><td colspan="6" style="text-align:center;color:var(--sec);padding:24px 0">本商暂无在职催收员</td></tr>
            </tbody>
          </table>
          <div class="note">持有数=当前在催案件；今日动作=通话+标注等跟进动作；容量余量=可接收新案件数（上限 {{ vlHoldCap }}）；今日回款=其持有案件当日确认回款。</div>
        </div>
      </template>

      <!--
        「协调员督导概览」表已删除：它不只是缺数据，而是**虚构了人**——写死了"钱协调""孙协调"
        两名并不存在的员工，连同他们的处理案件数/工单数/存证数/平均响应/质检风险/超时数。
        契约里没有任何按协调员聚合作业绩效的端点，这张表从头到尾没有数据来源。
        物业负责人会拿它去做绩效判断，所以它比一张空表危险得多。
      -->
    </template>

    <!-- 加载中占位（wb 尚未返回） -->
    <div v-else class="note" style="text-align:center;padding:48px 0">加载工作台数据中…</div>
  </div>
  <div v-else class="note" style="text-align:center;padding:48px 0">加载主体中…</div>
</template>
