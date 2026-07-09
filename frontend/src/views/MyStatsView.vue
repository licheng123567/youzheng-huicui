<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import DsDrawer from '../components/DsDrawer.vue'

// 我的业绩——按角色两套口径（对标原型 §我的业绩）：
//   · 催收员(CO)：服务商内部考核口径，回款×提成的批次结算钻取（数据源 GET /me/stats）。
//   · 物业协调员(PC)：本物业处置口径，法务送达/工单/回款标记/在办法务 的协作产能
//     （无专属统计端点，按 /cases + /batches + /workbench 现有数据实时聚合，不臆造）。
const auth = useAuth()
const isCoordinator = computed(() => auth.me?.role === 'PC')
const isCollector = computed(() => auth.me?.role === 'CO')

const loading = ref(false)
const stats = ref<any>(null)
const month = ref('')
const yuan = (c?: number | null) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number | null) => (r == null ? '—' : (r * 100).toFixed(1) + '%')

function ym(offset: number): string {
  const d = new Date()
  d.setMonth(d.getMonth() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const thisMonth = ym(0)
const lastMonth = ym(-1)
const curMonth = computed(() => stats.value?.month ?? month.value ?? thisMonth)

async function load() {
  loading.value = true
  const query: Record<string, any> = {}
  if (month.value) query.month = month.value
  const { data, error } = await api.GET('/me/stats', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载我的业绩失败'); return }
  stats.value = data
}
function pick(m: string) { month.value = m; load() }

// KPI 五宫格
const ICONS: Record<string, string> = {
  money: 'M12 1v22M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6',
  sms: 'M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z',
  check: 'M9 11l3 3L20 6M20 12v7a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h9',
  mine: 'M12 12a4 4 0 100-8 4 4 0 000 8M4 20a8 8 0 0116 0',
  wallet: 'M3 7h18v12H3zM16 12h3M3 7l3-3h12l3 3',
  stamp: 'M9 8a3 3 0 116 0c0 2-2 3-2 5h-2c0-2-2-3-2-5zM5 21h14M7 17h10v4H7z',
  cases: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01',
  warn: 'M10.3 3.9 1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0M12 9v4M12 17h.01',
}
const kpis = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { l: '当月回款', v: yuan(s.repayCents), i: 'money', c: '#15A35B' },
    { l: '接通率', v: pct(s.connectRate), i: 'sms', c: '#2563EB' },
    { l: '承诺兑现率', v: pct(s.promiseFulfillRate), i: 'check', c: '#E6A23C' },
    { l: '当月回款户数', v: String(s.repayCases ?? 0), i: 'mine', c: '#11A8B5' },
    { l: '当月提成', v: yuan(s.commissionCents), i: 'wallet', c: '#7C5CFC' },
  ]
})

const rows = computed<any[]>(() => stats.value?.rows ?? [])

function settleState(r: any): 'all' | 'part' | 'none' {
  if (!r.totalLineCount) return 'none'
  if (r.settledLineCount === r.totalLineCount) return 'all'
  if (r.settledLineCount > 0) return 'part'
  return 'none'
}

// Tab：待结算（还有待结提成/未全部结清的批次）/ 已结算完毕（全部结清的批次）/ 全部
const activeTab = ref<'unsettled' | 'settled' | 'all'>('unsettled')
const TABS = [
  { k: 'unsettled', l: '待结算' },
  { k: 'settled', l: '已结算完毕' },
  { k: 'all', l: '全部' },
] as const
// 各 tab 的批次计数（tab 标题徽标）
const tabCount = computed(() => ({
  unsettled: rows.value.filter((r: any) => settleState(r) !== 'all').length,
  settled: rows.value.filter((r: any) => settleState(r) === 'all').length,
  all: rows.value.length,
}))
function tabMatch(r: any): boolean {
  if (activeTab.value === 'all') return true
  if (activeTab.value === 'settled') return settleState(r) === 'all'
  return settleState(r) !== 'all'   // 待结算：未全部结清
}

// 批次筛选（项目/搜索批次号）——结算进度改由上方 tab 控制
const filter = reactive({ q: '', project: '' })
const projectOptions = computed(() => Array.from(new Set(rows.value.map((r: any) => r.project).filter(Boolean))))
const filteredRows = computed(() => rows.value.filter((r: any) => {
  if (!tabMatch(r)) return false
  if (filter.q && !(r.batch || '').includes(filter.q)) return false
  if (filter.project && r.project !== filter.project) return false
  return true
}))
function resetFilter() { filter.q = ''; filter.project = '' }

// 查看明细：右侧抽屉展示某批次的完整案件清单（已结算 + 未结算两组，始终全显，不受上方 tab 过滤影响）
const detailOpen = ref(false)
const detailRow = ref<any>(null)
function openDetail(r: any) { detailRow.value = r; detailOpen.value = true }
const settledLines = (r: any) => (r?.lines ?? []).filter((l: any) => l.settled)
const unsettledLines = (r: any) => (r?.lines ?? []).filter((l: any) => !l.settled)

// ═══════════ 物业协调员（PC）· 本物业处置口径 ═══════════
// 无专属统计端点：按现有 /cases + /batches + /workbench 实时聚合（对标原型 PC 我的业绩列）。
const DELIVERED_STAGE = 'DELIVERED'
const ACTIVE_LEGAL = ['FUNCTION_LETTER', 'LAWYER_LETTER', 'LITIGATION']
const pcRows = ref<any[]>([])
const pcKpi = computed(() => {
  const sum = (k: string) => pcRows.value.reduce((s, r) => s + (r[k] || 0), 0)
  return [
    { l: '法务送达完成', v: String(sum('delivered')), i: 'stamp', c: '#7C5CFC' },
    { l: '工单处理', v: String(sum('tickets')), i: 'cases', c: '#2563EB' },
    { l: '线下回款标记', v: String(sum('repayMarked')), i: 'money', c: '#15A35B' },
    { l: '缴费触达', v: String(sum('paylink')), i: 'sms', c: '#11A8B5' },
    { l: '在办法务', v: String(sum('legalActive')), i: 'warn', c: '#E6A23C' },
  ]
})
const pcFilter = reactive({ q: '', project: '' })
const pcProjectOptions = computed(() => Array.from(new Set(pcRows.value.map((r) => r.proj).filter(Boolean))))
// 基础筛选（项目/批次号），完结进度改由下方 tab 控制（与催收员分支同款交互）
const pcBaseRows = computed(() => pcRows.value.filter((r) => {
  if (pcFilter.q && !(r.batch || '').includes(pcFilter.q)) return false
  if (pcFilter.project && r.proj !== pcFilter.project) return false
  return true
}))
function pcReset() { pcFilter.q = ''; pcFilter.project = '' }
// 长历史降噪：按完结进度分 tab（进行中 / 已结项 / 全部），默认「进行中」——多批次时只看在办，历史归到「已结项」tab。
// 已结项 = 批次 status=CLOSED（全部处理完毕并结项）。与 CO 分支 待结算/已结算完毕/全部 tab 同构。
const PC_TABS = [
  { k: 'active', l: '进行中' },
  { k: 'closed', l: '已结项' },
  { k: 'all', l: '全部' },
] as const
const pcActiveTab = ref<'active' | 'closed' | 'all'>('active')
const pcTabCount = computed(() => ({
  active: pcBaseRows.value.filter((r) => !r.closed).length,
  closed: pcBaseRows.value.filter((r) => r.closed).length,
  all: pcBaseRows.value.length,
}))
const pcTabRows = computed(() => pcBaseRows.value.filter((r) =>
  pcActiveTab.value === 'all' ? true : pcActiveTab.value === 'closed' ? r.closed : !r.closed))

async function loadPc() {
  loading.value = true
  try {
    const [casesRes, batchesRes, wbRes] = await Promise.all([
      api.GET('/cases', { params: { query: { page: 1, size: 500 } } as any }),
      api.GET('/batches', { params: { query: { page: 1, size: 200 } } as any }),
      api.GET('/workbench', {} as any),
    ])
    const cases: any[] = ((casesRes.data as any)?.items ?? casesRes.data ?? []) as any[]
    const batches: any[] = ((batchesRes.data as any)?.items ?? batchesRes.data ?? []) as any[]
    const todos: any[] = ((wbRes.data as any)?.todos ?? []) as any[]
    // 批次 id → {code, project}
    const bmap = new Map<string, { code: string; status?: string }>()
    batches.forEach((b: any) => bmap.set(String(b.id), { code: b.code, status: b.status }))
    // 待处理工单 todo 计数（按 caseId）
    const ticketByCase = new Map<string, number>()
    todos.filter((t: any) => t.category === 'TICKET_RECEIPT').forEach((t: any) => {
      ticketByCase.set(String(t.caseId), (ticketByCase.get(String(t.caseId)) || 0) + 1)
    })
    // 按批次聚合
    const g = new Map<string, any>()
    cases.forEach((c: any) => {
      const bid = String(c.batchId)
      if (!g.has(bid)) g.set(bid, {
        batchId: bid, batch: bmap.get(bid)?.code || ('批次#' + bid), proj: c.projectName || '本物业',
        closed: bmap.get(bid)?.status === 'CLOSED',
        count: 0, delivered: 0, repayMarked: 0, repayMarkedCents: 0, tickets: 0, paylink: 0, legalActive: 0,
      })
      const row = g.get(bid)
      row.count++
      if (c.legalStage === DELIVERED_STAGE) row.delivered++
      if (ACTIVE_LEGAL.includes(c.legalStage)) row.legalActive++
      if (c.status === 'SETTLED') { row.repayMarked++; row.repayMarkedCents += (c.dueCents || 0) }
      row.tickets += ticketByCase.get(String(c.id)) || 0
    })
    pcRows.value = Array.from(g.values()).sort((a, b) => a.batch.localeCompare(b.batch))
  } catch { pcRows.value = [] }
  finally { loading.value = false }
}

onMounted(async () => {
  if (!auth.me) await auth.fetchMe()   // 直达路由时 me 可能尚未就绪，先确保角色已知再选口径
  // 仅 PC(协调员产能) / CO(催收员提成) 两套口径；其它角色不误拉 /me/stats（见模板兜底）。
  if (isCoordinator.value) loadPc(); else if (isCollector.value) load()
})
</script>

<template>
  <!-- ═══════════ 物业协调员（PC）：本物业处置口径 ═══════════ -->
  <div v-if="isCoordinator">
    <div class="toolbar" style="margin-bottom:14px">
      <span class="note" style="margin:0">统计周期（KPI）：</span>
      <span class="segctrl">
        <span :class="{ on: curMonth === thisMonth }" @click="month = thisMonth">本月</span>
        <span :class="{ on: curMonth === lastMonth }" @click="month = lastMonth">上月</span>
      </span>
      <input class="inp" type="month" :value="curMonth" aria-label="指定月份" style="min-width:150px"
             @change="month = ($event.target as HTMLInputElement).value || thisMonth">
      <span class="note" style="margin:0">本物业处置口径</span>
    </div>

    <!-- KPI 五宫格（法务送达完成 / 工单处理 / 线下回款标记 / 缴费触达 / 在办法务） -->
    <div class="kpis" style="grid-template-columns:repeat(5,1fr)" v-loading="loading">
      <div class="kpi" v-for="k in pcKpi" :key="k.l">
        <div class="ic" :style="{ background: k.c }">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path :d="ICONS[k.i]" /></svg>
        </div>
        <div class="n">{{ k.v }}</div>
        <div class="l">{{ k.l }}</div>
      </div>
    </div>

    <!-- 按批次 · 处置产能 -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>业绩（按批次）</div>
        <div class="ops"><span class="note" style="margin:0">本物业各批次的送达 / 工单 / 回款标记 / 在办法务</span></div>
      </div>
      <div class="alert info" style="margin-top:0">本物业处置口径：仅统计你所在物业的送达存证、工单处理、线下回款标记与在办法务；不含提成（协调员非提成制）。数据按当前案件实时聚合。</div>

      <!-- Tab：进行中 / 已结项 / 全部（默认进行中）——与催收员分支同款，多批次长历史降噪 -->
      <div class="dtabs" style="padding:6px 0 2px">
        <div v-for="t in PC_TABS" :key="t.k" class="t" :class="{ on: pcActiveTab === t.k }" @click="pcActiveTab = t.k">
          {{ t.l }}<span class="tag" :class="t.k === 'closed' ? 'suc' : t.k === 'active' ? 'war' : 'inf'" style="font-size:10px;padding:0 5px;margin-left:4px">{{ pcTabCount[t.k] }}</span>
        </div>
      </div>

      <div class="toolbar" style="margin:10px 0">
        <input class="inp" v-model="pcFilter.q" placeholder="搜索 批次号" aria-label="批次号搜索">
        <select class="inp" v-model="pcFilter.project" aria-label="项目筛选">
          <option value="">项目：全部</option>
          <option v-for="p in pcProjectOptions" :key="p" :value="p">{{ p }}</option>
        </select>
        <button class="btn df sm" @click="pcReset">重置</button>
      </div>
      <table>
        <thead>
          <tr><th>批次</th><th>项目</th><th>送达完成</th><th>回款标记额</th><th>工单处理</th><th>在办法务</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in pcTabRows" :key="r.batchId">
            <td>{{ r.batch }}</td>
            <td>{{ r.proj }}</td>
            <td class="num">{{ r.delivered }}</td>
            <td class="num">{{ yuan(r.repayMarkedCents) }}</td>
            <td class="num">{{ r.tickets }}</td>
            <td class="num">{{ r.legalActive }}</td>
          </tr>
          <tr v-if="!loading && !pcTabRows.length">
            <td colspan="6" class="note" style="text-align:center;padding:24px 0">
              {{ pcActiveTab === 'active' ? '暂无进行中批次。' : pcActiveTab === 'closed' ? '暂无已结项批次。' : '暂无本物业批次数据。' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- ═══════════ 催收员（CO）：服务商内部考核口径 ═══════════ -->
  <div v-else-if="isCollector">
    <!-- 统计周期切换 -->
    <div class="toolbar" style="margin-bottom:14px">
      <span class="note" style="margin:0">统计周期（KPI）：</span>
      <span class="segctrl">
        <span :class="{ on: curMonth === thisMonth }" @click="pick(thisMonth)">本月</span>
        <span :class="{ on: curMonth === lastMonth }" @click="pick(lastMonth)">上月</span>
      </span>
      <input class="inp" type="month" :value="curMonth" aria-label="指定月份" style="min-width:150px"
             @change="pick(($event.target as HTMLInputElement).value || thisMonth)">
      <span class="note" style="margin:0">服务商内部考核口径 · 平台只考核到服务商</span>
    </div>

    <!-- KPI 五宫格 -->
    <div class="kpis" style="grid-template-columns:repeat(5,1fr)" v-loading="loading">
      <div class="kpi" v-for="k in kpis" :key="k.l">
        <div class="ic" :style="{ background: k.c }">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path :d="ICONS[k.i]" /></svg>
        </div>
        <div class="n">{{ k.v }}</div>
        <div class="l">{{ k.l }}</div>
      </div>
    </div>

    <!-- 提成汇总三宫格（全时段累计） -->
    <div class="kpis" style="grid-template-columns:repeat(3,1fr);margin-top:12px">
      <div class="kpi"><div class="n">{{ yuan(stats?.totalCommissionCents) }}</div><div class="l">累计提成（全部）</div></div>
      <div class="kpi"><div class="n" style="color:var(--success)">{{ yuan(stats?.settledCommissionCents) }}</div><div class="l">已结提成</div></div>
      <div class="kpi"><div class="n" style="color:var(--warning)">{{ yuan(stats?.unsettledCommissionCents) }}</div><div class="l">待结提成</div></div>
    </div>

    <!-- 批次主列表：回款 + 结算进度，Tab 分待结算/已结算完毕，点「查看明细」展开案件级明细 -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>按批次 · 回款与结算</div>
        <div class="ops"><span class="note" style="margin:0">按「查看明细」展开案件清单</span></div>
      </div>

      <!-- Tab：待结算 / 已结算完毕 / 全部 -->
      <div class="dtabs" style="padding:0 0 2px">
        <div v-for="t in TABS" :key="t.k" class="t" :class="{ on: activeTab === t.k }" @click="activeTab = t.k">
          {{ t.l }}<span class="tag" :class="t.k === 'settled' ? 'suc' : t.k === 'unsettled' ? 'war' : 'inf'" style="font-size:10px;padding:0 5px;margin-left:4px">{{ tabCount[t.k] }}</span>
        </div>
      </div>

      <div class="alert info" style="margin-top:10px">提成比例由服务商内部设定；周期结清后由服务商结算给你。结案后业主信息脱敏（BR-M8-09），仅展示 房号+脱敏姓名+回款额+提成，不含电话/跟进。</div>
      <div class="toolbar" style="margin:10px 0">
        <input class="inp" v-model="filter.q" placeholder="搜索 批次号" aria-label="批次号搜索">
        <select class="inp" v-model="filter.project" aria-label="项目筛选">
          <option value="">项目：全部</option>
          <option v-for="p in projectOptions" :key="p" :value="p">{{ p }}</option>
        </select>
        <button class="btn df sm" @click="resetFilter">重置</button>
      </div>

      <table>
        <thead>
          <tr>
            <th>批次</th><th>项目</th><th>持有</th><th>回款额</th><th>回款率</th>
            <th>比例</th><th>提成额</th><th>已结提成</th><th>待结提成</th><th>结算进度</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in filteredRows" :key="r.batchId">
            <td>{{ r.batch }}</td>
            <td>{{ r.project }}</td>
            <td class="num">{{ r.holdCount ?? 0 }}</td>
            <td class="num">{{ yuan(r.repayCents) }}</td>
            <td class="num">{{ pct(r.repayRate) }}</td>
            <td class="num">{{ pct(r.rate) }}</td>
            <td class="num">{{ yuan(r.commissionCents) }}</td>
            <td class="num" style="color:var(--success)">{{ yuan(r.settledCommissionCents) }}</td>
            <td class="num" style="color:var(--warning)">{{ yuan(r.unsettledCommissionCents) }}</td>
            <td>
              <span class="tag" :class="settleState(r) === 'all' ? 'suc' : settleState(r) === 'part' ? 'war' : 'inf'">
                {{ settleState(r) === 'all' ? '全部结清' : settleState(r) === 'part' ? '部分结清' : '未结算' }}
                <template v-if="r.totalLineCount"> {{ r.settledLineCount }}/{{ r.totalLineCount }}</template>
              </span>
            </td>
            <td><a class="btn txt" @click="openDetail(r)">查看明细</a></td>
          </tr>
          <tr v-if="!loading && !filteredRows.length">
            <td colspan="11" class="note" style="text-align:center;padding:24px 0">{{ rows.length ? '该分类下暂无批次。' : '暂无业绩数据。' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 批次明细抽屉：该批次已结算 + 未结算完整清单（始终全显，不受上方 tab 过滤影响） -->
    <DsDrawer v-model="detailOpen" :title="detailRow ? detailRow.batch + ' · 案件明细' : '案件明细'" :width="640">
      <template v-if="detailRow">
        <!-- 批次汇总 -->
        <div class="kpis" style="grid-template-columns:repeat(4,1fr);margin-top:0">
          <div class="kpi"><div class="n">{{ yuan(detailRow.repayCents) }}</div><div class="l">回款额</div></div>
          <div class="kpi"><div class="n">{{ yuan(detailRow.commissionCents) }}</div><div class="l">提成额</div></div>
          <div class="kpi"><div class="n" style="color:var(--success)">{{ yuan(detailRow.settledCommissionCents) }}</div><div class="l">已结提成</div></div>
          <div class="kpi"><div class="n" style="color:var(--warning)">{{ yuan(detailRow.unsettledCommissionCents) }}</div><div class="l">待结提成</div></div>
        </div>
        <div class="note" style="margin:8px 0 0">
          {{ detailRow.project }} · 比例 {{ pct(detailRow.rate) }} · 结算进度
          <span class="tag" :class="settleState(detailRow) === 'all' ? 'suc' : settleState(detailRow) === 'part' ? 'war' : 'inf'">
            {{ settleState(detailRow) === 'all' ? '全部结清' : settleState(detailRow) === 'part' ? '部分结清' : '未结算' }}
            <template v-if="detailRow.totalLineCount"> {{ detailRow.settledLineCount }}/{{ detailRow.totalLineCount }}</template>
          </span>
        </div>

        <div v-if="!(detailRow.lines ?? []).length" class="note" style="text-align:center;padding:24px 0">本批次暂无回款记录（仅持有 {{ detailRow.holdCount }} 户，待回款）。</div>
        <template v-else>
          <!-- 已结算清单 -->
          <div class="sec-title" style="margin-top:16px"><span class="tag suc" style="margin-right:6px">已结算</span>{{ settledLines(detailRow).length }} 户</div>
          <table v-if="settledLines(detailRow).length">
            <thead><tr><th>业主(脱敏)</th><th>房号</th><th>回款额</th><th>到账日</th><th>结清日</th><th>我的提成</th></tr></thead>
            <tbody>
              <tr v-for="l in settledLines(detailRow)" :key="l.caseId + l.paidAt">
                <td>{{ l.ownerMasked }}</td><td>{{ l.room }}</td>
                <td class="num">{{ yuan(l.repayCents) }}</td>
                <td class="num">{{ l.paidAt }}</td>
                <td class="num">{{ l.closedAt ?? '—' }}</td>
                <td class="num" style="color:var(--success)">{{ yuan(l.commissionCents) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="note" style="font-size:12px">本批次暂无已结算案件。</div>

          <!-- 未结算清单 -->
          <div class="sec-title" style="margin-top:16px"><span class="tag war" style="margin-right:6px">未结算</span>{{ unsettledLines(detailRow).length }} 户</div>
          <table v-if="unsettledLines(detailRow).length">
            <thead><tr><th>业主(脱敏)</th><th>房号</th><th>回款额</th><th>到账日</th><th>结清日</th><th>我的提成</th></tr></thead>
            <tbody>
              <tr v-for="l in unsettledLines(detailRow)" :key="l.caseId + l.paidAt">
                <td>{{ l.ownerMasked }}</td><td>{{ l.room }}</td>
                <td class="num">{{ yuan(l.repayCents) }}</td>
                <td class="num">{{ l.paidAt }}</td>
                <td class="num">{{ l.closedAt ?? '—' }}</td>
                <td class="num" style="color:var(--warning)">{{ yuan(l.commissionCents) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="note" style="font-size:12px">本批次全部结清，无未结算案件。</div>
        </template>
      </template>
      <template #footer><el-button @click="detailOpen = false">关闭</el-button></template>
    </DsDrawer>
  </div>

  <!-- 兜底：非 CO/PC 角色（SA/SE/PL/VL）无个人业绩口径，给占位而非误当催收员拉 /me/stats -->
  <div v-else class="card">
    <div class="card-h"><div class="t"><span class="bar"></span>我的业绩</div></div>
    <div class="note" style="padding:24px 0;text-align:center">「我的业绩」仅面向催收员（提成）与物业协调员（处置产能）。当前角色无个人业绩口径。</div>
  </div>
</template>
