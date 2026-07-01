<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'
import { permLabel } from '../constants/permissions'
import { orgTypeLabel, caseStatusLabel, activityTypeLabel, todoCategoryLabel } from '../constants/enums'

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

// ===== cockpit master-detail（仅新增「读取/选中」型逻辑，不改写操作）=====
// 选中态：本地 ref；与 KPI 筛选(filterKey)、tab 解耦
const cockpitId = ref<string>('')
const cpDetail = ref<any>(null)         // GET /cases/{id} → { case, timeline, ... }
const cpLoading = ref(false)

// 时间线类型 → 颜色（与案件页一致；缺省 inf）
const tlTag = (t?: string) => {
  const k = String(t || '').toUpperCase()
  if (k === 'CALL') return 'pri'
  if (k === 'PROMISE' || k === 'REPAY') return 'suc'
  if (k === 'TICKET' || k === 'LEGAL') return 'war'
  return 'inf'
}
const caseStatusTag = (s?: string) => {
  const m: Record<string, string> = {
    SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
    PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
    WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
  }
  return m[s ?? ''] ?? 'inf'
}
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 左 worklist：复用 filterKey 过滤后的 todos（todo 字段不足时优雅降级）
const worklist = computed<any[]>(() => todos.value)
// 概览：紧急（HIGH）提醒，取全量 todos（不受 filterKey 限制）
const allTodos = computed<any[]>(() => wb.value?.todos ?? [])
const worklistUrgent = computed<any[]>(() => allTodos.value.filter((t: any) => t.urgency === 'HIGH').slice(0, 6))

// wl-tabs：由 todos 的 category 分布派生（全部 + 各分类计数），点击复用 filterKey
const wlTabs = computed<Array<{ k: string; l: string; n: number }>>(() => {
  const list = allTodos.value
  const counts: Record<string, number> = {}
  for (const t of list) counts[t.category] = (counts[t.category] || 0) + 1
  const tabs: Array<{ k: string; l: string; n: number }> = [{ k: '', l: '全部', n: list.length }]
  for (const cat of Object.keys(counts)) tabs.push({ k: cat, l: CAT_LABEL[cat] || cat, n: counts[cat] })
  return tabs
})

// 今日概览进度（演示态：已选/进入过的视为已处理参考；基于 todos 总量给出剩余）
const ovTotal = computed<number>(() => allTodos.value.length || 0)
const ovRemain = computed<number>(() => allTodos.value.length)
const ovPct = computed<number>(() => 0) // 无「已处理」数据来源，进度条以剩余件数为主语义（保守置 0）

const cpCase = computed<any>(() => cpDetail.value?.case ?? null)
const cpTimeline = computed<any[]>(() => (cpDetail.value?.timeline ?? []).slice(0, 6))
const cpInitial = computed<string>(() => {
  const n = cpCase.value?.ownerName
  return n ? String(n).charAt(0) : '案'
})
// 选中 todo 标题（无 case 字段时降级展示）
const cpTitle = computed<string>(() => {
  const t = (allTodos.value.find((x: any) => x.caseId === cockpitId.value))
  return t?.title || cockpitId.value
})

async function selectWl(caseId?: string) {
  if (!caseId) return
  cockpitId.value = caseId
  cpDetail.value = null
  cpLoading.value = true
  try {
    const { data } = await api.GET('/cases/{id}', { params: { path: { id: caseId } } })
    cpDetail.value = data
  } finally {
    cpLoading.value = false
  }
}
function fullScreen() { if (cockpitId.value) router.push(`/cases/${cockpitId.value}`) }

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
}

// KPI label → icon/color/trend 映射（回退关键词匹配）
function kpiMeta(label: string): { i: string; c: string; t: string; tx: string } {
  const exact: Record<string, { i: string; c: string; t: string; tx: string }> = {
    '回款金额': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 12%' },
    '全平台回款': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 15%' },
    '本月回款': { i: 'money', c: '#15A35B', t: 'up', tx: '▲ 12%' },
    '回款案件数': { i: 'check', c: '#2563EB', t: 'up', tx: '▲ 8' },
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

// 经营 KPI 卡片（API wb.kpis 驱动，补 icon/color/trend）
const dashboardKpis = computed(() => {
  return (wb.value?.kpis ?? []).map((k: any) => {
    const meta = kpiMeta(k.label)
    return { l: k.label, n: k.value, i: meta.i, c: meta.c, t: meta.t, tx: meta.tx }
  })
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
</script>

<template>
  <div v-if="me">
    <!-- ================================================================ -->
    <!--  Cockpit：一线办案角色（CO/PC）· 今日驾驶舱                          -->
    <!-- ================================================================ -->
    <template v-if="wb?.layout === 'cockpit'">
      <!-- ① KPI 可点即筛选条（BR-M4-20a · 驾驶舱 ck-chip） -->
      <div v-if="wb?.kpis?.length" class="cockpit-kpis">
        <div
          v-for="k in wb.kpis"
          :key="k.label"
          class="ck-chip"
          :class="{ on: filterKey === k.filterKey, ro: !k.filterKey }"
          @click="k.filterKey && (filterKey = filterKey === k.filterKey ? '' : k.filterKey)"
        >
          <div class="n">{{ k.value }}</div>
          <div class="l">{{ k.label }}</div>
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

        <!-- 右：选中→案件预览（一键进三栏）；未选→今日概览 -->
        <div class="cp-detail" :class="{ embed: cockpitId }">
          <!-- 已选：轻量案件预览 -->
          <div v-if="cockpitId" class="cp-embed">
            <div class="cp-embed-h">
              <div class="t">
                {{ cpCase?.ownerName || cpTitle }}
                <template v-if="cpCase?.room"> · {{ cpCase.room }}</template>
                <span v-if="cpCase?.status" class="tag" :class="caseStatusTag(cpCase.status)" :title="cpCase.status">{{ caseStatusLabel(cpCase.status) }}</span>
              </div>
              <div class="ops">
                <button class="btn pl sm" @click="fullScreen()">全屏 ⤢</button>
              </div>
            </div>

            <div v-if="cpLoading" class="note" style="padding:24px 0;text-align:center">加载案件预览中…</div>
            <template v-else-if="cpDetail">
              <div class="portrait-top" style="margin-bottom:14px">
                <div class="portrait-av" style="background:var(--primary);width:46px;height:46px;border-radius:50%;color:#fff;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:600;flex:none">{{ cpInitial }}</div>
                <div class="portrait-id" style="flex:1">
                  <div class="nm">{{ cpCase?.ownerName || '—' }}</div>
                  <div class="sub">{{ cpCase?.room || '—' }} · 户号 {{ cpCase?.acctNo || '—' }}</div>
                </div>
                <div style="text-align:right">
                  <div class="num" style="font-size:20px;font-weight:700;color:var(--danger)">{{ yuan(cpCase?.dueCents) }}</div>
                  <div class="note" style="margin:0">应收欠费</div>
                </div>
              </div>
              <div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px">
                <span v-if="cpCase?.pool" class="tag inf">{{ cpCase.pool }}</span>
                <span v-if="cpCase?.redacted" class="tag inf">已脱敏</span>
                <span v-if="cpCase?.reduceAfterCents != null && cpCase.reduceAfterCents !== cpCase.dueCents" class="tag war">减免后 {{ yuan(cpCase.reduceAfterCents) }}</span>
              </div>

              <div class="sec-title">最近动态</div>
              <div class="tl" v-if="cpTimeline.length">
                <div class="e" v-for="ev in cpTimeline" :key="ev.id">
                  <span class="tag" :class="tlTag(ev.type)" :title="ev.type">{{ activityTypeLabel(ev.type) }}</span>
                  {{ ev.content }}
                  <b style="float:right;color:var(--ph);font-weight:400">{{ String(ev.createdAt || '').slice(0, 16).replace('T', ' ') }}</b>
                </div>
              </div>
              <div v-else class="note">暂无事件。</div>

              <button class="btn pl cp-enter" style="width:100%;margin-top:18px" @click="fullScreen()">进案件作业 →</button>
            </template>
            <div v-else class="note" style="padding:24px 0;text-align:center">案件预览加载失败，可点「全屏 ⤢」进入。</div>
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

    <!-- ③ 当前主体（契约 GET /me）— 所有角色共用 -->
    <div v-if="wb" class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>当前主体</div>
        <div class="ops"><span class="note" style="margin:0">契约 GET /me</span></div>
      </div>
      <div class="desc">
        <div class="r"><span class="k">账号 ID</span><span class="v">{{ me.accountId }}</span></div>
        <div class="r"><span class="k">姓名</span><span class="v">{{ me.name }}</span></div>
        <div class="r">
          <span class="k">角色</span>
          <span class="v">{{ me.role }}（工作台 {{ wb?.layout === 'cockpit' ? '今日驾驶舱' : '仪表盘' }}）</span>
        </div>
        <div class="r"><span class="k">组织</span><span class="v">{{ me.org?.name }}（<span :title="me.org?.type">{{ orgTypeLabel(me.org?.type) }}</span>）</span></div>
        <div class="r">
          <span class="k">数据范围</span>
          <span class="v">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量（dataScope=null）' }}</span>
        </div>
        <div class="r" style="border-bottom:none">
          <span class="k">权限点</span>
          <span class="v" style="display:flex;flex-wrap:wrap;gap:6px">
            <span v-for="p in me.permissions" :key="p" class="tag inf" :title="p">{{ permLabel(p) }}</span>
          </span>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="note" style="text-align:center;padding:48px 0">加载主体中…</div>
</template>
