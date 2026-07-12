<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 经营报表 · 对齐高保真 index.html view==='reports'（行 1316-1515）
// SA/SE=平台损益；PL=物业视角；VL=服务商视角
const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

const data = ref<any>(null)
const reportRange = ref('')   // YYYY-MM；空=全部月份

const scopeLabel: Record<string, string> = { SA: '全局', SE: '范围内', PL: '本物业', PC: '本物业', VL: '本服务商', CO: '本人' }

async function load() {
  if (isPlatform.value) return          // 平台的数全走「穿透统计」卡（load 的结果已无人消费）
  const q: any = { dimension: 'batch', page: 1, size: 50 }
  if (reportRange.value) q.month = reportRange.value          // 后端 month=YYYY-MM，按案件建档月过滤
  const { data: d, error } = await api.GET('/reports/operation', { params: { query: q } as any })
  if (error) { ElMessage.error('报表加载失败'); return }
  data.value = d
}

function applyFilter() { load(); loadVl(); if (isPlatform.value) loadDrill() }

async function exportReport() {
  const { error } = await api.POST('/reports/export', { body: { report: 'operation', format: 'xlsx' } as any })
  if (error) { ElMessage.error('导出失败：' + ((error as any)?.message ?? '无 report.export 权限')); return }
  ElMessage.success('导出任务已提交')
}

// KPI 读 data.kpis（ReportKpi[]·{label,kind,amountCents,rate,count}）——此前误读 data.summary（后端从不返回该字段）恒空。
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(1) + '%')
const kpis = computed(() => {
  const arr = data.value?.kpis
  if (!Array.isArray(arr)) return []
  return arr.map((k: any) => ({
    l: k.label,
    n: k.kind === 'MONEY' ? yuan(k.amountCents) : k.kind === 'RATE' ? pct(k.rate) : (k.count ?? '—'),
  }))
})

// VL 服务商视角三块真数据（催收员产能 / 佣金汇总 / 团队即时看板 US-M10-03）
const coProd = ref<any[]>([])      // 催收员产能：/reports/operation?dimension=collector
const coComm = ref<any[]>([])      // 佣金汇总：/co-commissions（应得/已付/未付）
const vlTeam = ref<any[]>([])      // 团队即时看板：/providers/{id}/collector-capacity
const vlHoldCap = ref(0)
async function loadVl() {
  if (role.value !== 'VL') return
  const orgId = auth.me?.org?.id
  const [prod, comm, cap] = await Promise.all([
    api.GET('/reports/operation', { params: { query: { dimension: 'collector' } } as any }),
    api.GET('/co-commissions', { params: { query: { page: 1, size: 50 } } as any }),
    orgId ? api.GET('/providers/{id}/collector-capacity', { params: { path: { id: String(orgId) } } } as any) : Promise.resolve({ data: null }),
  ])
  coProd.value = (prod.data as any)?.rows ?? []
  coComm.value = (comm.data as any)?.items ?? []
  vlTeam.value = (cap.data as any)?.items ?? []
  vlHoldCap.value = (cap.data as any)?.holdCap ?? 0
}

// 平台 KPI 图标 path
const ic: Record<string, string> = {
  money: 'M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  chart: 'M3 3v18h18 M7 14l3-3 3 3 5-6',
  cases: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6',
  rate: 'M12 2 2 7l10 5 10-5z M2 17l10 5 10-5 M2 12l10 5 10-5',
}

// PL 回款率趋势（演示态条形）
const repayTrend = computed(() => {
  if (!data.value?.rows) return []
  return [...data.value.rows].slice(0, 6).map((r: any) => ({
    m: r.dimName?.slice(0, 7) || r.dimName || '—',
    rate: r.dueCents ? Math.round((r.repayCents || 0) / r.dueCents * 100) : 0,
  }))
})

// PL 催收周期分布（演示态）
const cycleDist = computed(() => [
  { seg: '≤30天', n: 12, pct: 40 },
  { seg: '31-60天', n: 9, pct: 30 },
  { seg: '61-90天', n: 6, pct: 20 },
  { seg: '>90天', n: 3, pct: 10 },
])

// 能力用量 KPI
const capBilling = computed(() => {
  if (!data.value?.rows) return []
  const stt = data.value.rows.reduce((s: number, r: any) => s + ((r.sttMinutes) || 0), 0)
  const sms = data.value.rows.reduce((s: number, r: any) => s + ((r.smsCount) || 0), 0)
  return [
    { item: 'STT 分钟', qty: stt || '—' },
    { item: '短信条数', qty: sms || '—' },
    { item: '存证次数', qty: '—' },
    { item: '法律服务', qty: '—' },
  ]
})

// ── 平台穿透报表（v1.25.0）──
// 用户诉求：「平台的经营报表可以根据物业公司的聚合向下穿透统计，也可以根据服务商穿透统计。」
// 此前平台视角是两张写死「暂无数据」的空表（区域损益/佣金毛利）+ 一张批次平表——
// 既看不出哪家物业/哪家服务商贡献多少，更钻不下去。
//
// 两条链路：
//   物业：各物业公司 → 点某家 → 它的项目 → 点某个 → 该项目的批次
//   服务商：各服务商 → 点某家 → 它承接的批次
// 服务商链路的口径是**双侧的**：在催盘子认当前归属、催回的钱认到账快照——
// 所以「已结项」的批次会以「应收 0 / 已收 >0 / 0 件」出现，那是「钱催回来了但盘子已收走」，不是脏数据。
type Crumb = { label: string; dimension: string; propertyId?: string; providerId?: string; projectId?: string }
const drillMode = ref<'property' | 'provider'>('property')
const crumbs = ref<Crumb[]>([])
const drillRows = ref<any[]>([])
const drillKpis = ref<any[]>([])
const drillLoading = ref(false)
// 两套列：催收进度（应收/回款/案件）与佣金双线（收佣/付佣各三态）。挤在一张表里会宽到没法看。
const showComm = ref(false)

const rootCrumb = computed<Crumb>(() => drillMode.value === 'property'
  ? { label: '全部物业公司', dimension: 'property' }
  : { label: '全部服务商', dimension: 'provider' })
const curCrumb = computed<Crumb>(() => crumbs.value[crumbs.value.length - 1] ?? rootCrumb.value)
// 当前层级的表头第一列叫什么（物业/项目/批次/服务商）
const DIM_COL: Record<string, string> = {
  property: '物业公司', provider: '服务商', project: '项目', batch: '批次',
}
const drillCanDeeper = computed(() => curCrumb.value.dimension !== 'batch')

async function loadDrill() {
  drillLoading.value = true
  const c = curCrumb.value
  const q: any = { dimension: c.dimension }
  if (reportRange.value) q.month = reportRange.value          // 月份筛子对每一层都生效
  if (c.propertyId) q.propertyId = c.propertyId
  if (c.providerId) q.providerId = c.providerId
  if (c.projectId) q.projectId = c.projectId
  const { data: d, error } = await api.GET('/reports/operation', { params: { query: q } as any })
  drillLoading.value = false
  if (error) { ElMessage.error('报表加载失败'); drillRows.value = []; return }
  drillRows.value = (d as any)?.rows ?? []
  drillKpis.value = ((d as any)?.kpis ?? []).map((k: any) => ({
    l: k.label,
    n: k.kind === 'MONEY' ? yuan(k.amountCents) : k.kind === 'RATE' ? pct(k.rate) : (k.count ?? '—'),
  }))
}

function switchMode(m: 'property' | 'provider') {
  drillMode.value = m
  crumbs.value = []
  loadDrill()
}

/** 点某一行往下钻。物业→项目→批次；服务商→批次（批次是末层，不再往下）。 */
function drillInto(row: any) {
  if (!drillCanDeeper.value || !row.dimKey) return
  const c = curCrumb.value
  if (c.dimension === 'property') {
    crumbs.value.push({ label: row.dimName, dimension: 'project', propertyId: String(row.dimKey) })
  } else if (c.dimension === 'provider') {
    crumbs.value.push({ label: row.dimName, dimension: 'batch', providerId: String(row.dimKey) })
  } else if (c.dimension === 'project') {
    crumbs.value.push({ ...c, label: row.dimName, dimension: 'batch', projectId: String(row.dimKey) })
  }
  loadDrill()
}

/** 面包屑回退：-1=回到根 */
function backTo(i: number) {
  crumbs.value = i < 0 ? [] : crumbs.value.slice(0, i + 1)
  loadDrill()
}

const isPlatform = computed(() => role.value === 'SA' || role.value === 'SE')

onMounted(() => { load(); loadVl(); if (isPlatform.value) loadDrill() })
</script>

<template>
  <div>
    <!-- 工具栏 -->
    <div class="toolbar" style="margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <span class="note" style="margin:0">报表范围：{{ scopeLabel[role] || '全局' }}（导出仅含范围内数据）</span>
        <!-- 只留真的筛子：月份（后端 month 参数，按案件建档月过滤）。
             原先还有「全部区域」「按月/按季/按年」两个下拉——applyFilter 从不把它们发出去，
             后端也没有对应参数，纯装饰。删掉：假开关比没有开关更坏。 -->
        <input class="inp" type="month" v-model="reportRange" style="min-width:140px" placeholder="全部月份" />
        <button class="btn df sm" @click="applyFilter">应用筛选</button>
        <button v-if="reportRange" class="btn sm" @click="reportRange = ''; applyFilter()">清除</button>
      </div>
      <button v-if="auth.has('report.export')" class="btn sm" @click="exportReport">导出</button>
    </div>

    <!-- KPI 卡片：**平台不出这组**——它与「穿透统计」卡里的 KPI 在根层完全重复，
         而后者会随下钻层级变（钻进翠湖就是翠湖的数）。留一组、且是会变的那组。 -->
    <div class="kpis" v-if="data && !isPlatform">
      <div class="kpi" v-for="s in kpis" :key="s.l">
        <div class="n">{{ s.n }}</div><div class="l">{{ s.l }}</div>
      </div>
    </div>

    <!-- ═══ SA/SE：平台视角 —— 穿透统计（v1.25.0）═══ -->
    <template v-if="isPlatform">
      <div class="card">
        <div class="card-h">
          <div class="t"><span class="bar"></span>穿透统计</div>
          <div class="ops">
            <div class="segctrl">
              <span :class="{ on: drillMode === 'property' }" @click="switchMode('property')">按物业公司</span>
              <span :class="{ on: drillMode === 'provider' }" @click="switchMode('provider')">按服务商</span>
            </div>
          </div>
        </div>

        <!-- 面包屑：点哪层回哪层 -->
        <div class="note" style="margin:0 0 8px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">
          <a class="btn txt" style="padding:0" @click="backTo(-1)">{{ rootCrumb.label }}</a>
          <template v-for="(c, i) in crumbs" :key="i">
            <span>›</span>
            <a v-if="i < crumbs.length - 1" class="btn txt" style="padding:0" @click="backTo(i)">{{ c.label }}</a>
            <b v-else>{{ c.label }}</b>
          </template>
        </div>

        <!-- 当前层级的 KPI（合计口径随筛子变；下钻后的合计必与上一层那一行严丝合缝） -->
        <div class="kpis" data-testid="drill-kpis" style="margin-bottom:10px">
          <div class="kpi" v-for="k in drillKpis" :key="k.l">
            <div class="n">{{ k.n }}</div><div class="l">{{ k.l }}</div>
          </div>
        </div>

        <!-- 佣金双线：收佣=物业付给平台(IN)，付佣=平台付给服务商(OUT)。口径与【结算对账】页逐字一致。 -->
        <div class="segctrl" style="margin-bottom:8px">
          <span :class="{ on: !showComm }" @click="showComm = false">催收进度</span>
          <span :class="{ on: showComm }" @click="showComm = true">佣金双线</span>
        </div>

        <div style="overflow-x:auto">
        <table>
          <thead>
            <tr v-if="!showComm">
              <th>{{ DIM_COL[curCrumb.dimension] ?? '维度' }}</th>
              <th style="width:140px">应收总额</th>
              <th style="width:140px">已回款</th>
              <th style="width:110px">回款率</th>
              <th style="width:90px">案件数</th>
              <th v-if="drillCanDeeper" style="width:90px">操作</th>
            </tr>
            <tr v-else>
              <th>{{ DIM_COL[curCrumb.dimension] ?? '维度' }}</th>
              <th style="width:120px">应收佣金</th>
              <th style="width:120px">已收佣金</th>
              <th style="width:120px">待收佣金</th>
              <th style="width:120px">应付佣金</th>
              <th style="width:120px">已付佣金</th>
              <th style="width:120px">待付佣金</th>
              <th style="width:110px">佣金毛利</th>
              <th v-if="drillCanDeeper" style="width:80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in drillRows" :key="r.dimKey ?? r.dimName"
                :class="{ 'row-click': drillCanDeeper && r.dimKey }" @click="drillInto(r)">
              <td>
                <b>{{ r.dimName }}</b>
                <!-- 应收 0 但有回款：该批次已被结项，盘子收走了，但这家当年催回的钱仍算它的（V914 快照） -->
                <span v-if="!r.dueCents && r.repayCents" class="tag war" style="margin-left:6px">已结项·仅历史回款</span>
                <!-- 漏配付佣比例：应付按 0 计入，会低估平台欠款——必须让人看见 -->
                <span v-if="showComm && r.outRateMissingBatches" class="tag dan" style="margin-left:6px"
                      :title="'有 ' + r.outRateMissingBatches + ' 个批次未设付佣比例，其应付佣金按 0 计入（低估）'">
                  {{ r.outRateMissingBatches }} 个批次未设付佣比例
                </span>
              </td>
              <template v-if="!showComm">
                <td class="num">{{ yuan(r.dueCents) }}</td>
                <td class="num">{{ yuan(r.repayCents) }}</td>
                <td class="num">{{ pct(r.repayRate) }}</td>
                <td class="num">{{ r.caseCount ?? 0 }}</td>
              </template>
              <template v-else>
                <td class="num">{{ yuan(r.commInDueCents) }}</td>
                <td class="num">{{ yuan(r.commInSettledCents) }}</td>
                <td class="num"><b :style="{ color: r.commInUnsettledCents ? 'var(--warning)' : '' }">{{ yuan(r.commInUnsettledCents) }}</b></td>
                <td class="num">{{ yuan(r.commOutDueCents) }}</td>
                <td class="num">{{ yuan(r.commOutSettledCents) }}</td>
                <td class="num"><b :style="{ color: r.commOutUnsettledCents ? 'var(--warning)' : '' }">{{ yuan(r.commOutUnsettledCents) }}</b></td>
                <td class="num">{{ yuan((r.commInDueCents ?? 0) - (r.commOutDueCents ?? 0)) }}</td>
              </template>
              <td v-if="drillCanDeeper" @click.stop>
                <a v-if="r.dimKey" class="btn txt" @click="drillInto(r)">下钻 ›</a>
                <span v-else class="note" style="margin:0">—</span>
              </td>
            </tr>
            <tr v-if="!drillRows.length && !drillLoading">
              <td :colspan="showComm ? 9 : 6" class="note" style="text-align:center;padding:32px 0">暂无数据</td>
            </tr>
          </tbody>
        </table>
        </div>

        <div class="note" v-if="showComm">
          <span>
            收佣=物业付给平台，付佣=平台付给服务商；<b>口径与【结算对账】页逐字一致</b>（每笔回款 × 该批次比率逐笔计算，
            已收/已付看支付申请单是否结清，不计已冲正）。待收/待付即「还没结清的部分」，可到【结算对账】开支付申请单。
          </span>
        </div>

        <div class="note" v-if="drillMode === 'provider'">
          服务商口径是双侧的：<b>在催盘子按当前归属、催回的钱按到账快照</b>——批次结项换商后，前一家催回的钱仍算前一家的，
          不会随案件归属改变而漂移。故会出现「应收 0 / 已回款 &gt; 0」的行（盘子已收走，钱是它当年催的）。
        </div>
      </div>
    </template>

    <!-- ═══ PL：物业视角 ═══ -->
    <template v-else-if="role === 'PL' || role === 'PC'">
      <!-- 回款率趋势 -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>回款率趋势</div><div class="ops"><span class="note" style="margin:0">{{ scopeLabel[role] || '本物业' }}</span></div></div>
        <div v-for="t in repayTrend" :key="t.m" style="display:flex;align-items:center;gap:10px;margin:8px 0">
          <span style="width:72px;font-size:13px">{{ t.m }}</span>
          <div style="flex:1;background:#f0f0f0;border-radius:4px;height:14px">
            <div :style="{ width: t.rate + '%', background: 'var(--primary)', height: '14px', borderRadius: '4px' }"></div>
          </div>
          <span class="num" style="width:48px">{{ t.rate }}%</span>
        </div>
        <div v-if="!repayTrend.length" class="note">暂无回款趋势数据</div>
      </div>

      <!-- 催收周期分布 -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>催收周期分布</div><div class="ops"><span class="note" style="margin:0">{{ scopeLabel[role] || '本物业' }}</span></div></div>
        <div v-for="d in cycleDist" :key="d.seg" style="display:flex;align-items:center;gap:10px;margin:8px 0">
          <span style="width:80px;font-size:13px">{{ d.seg }}</span>
          <div style="flex:1;background:#f0f0f0;border-radius:4px;height:14px">
            <div :style="{ width: d.pct + '%', background: 'var(--primary)', height: '14px', borderRadius: '4px' }"></div>
          </div>
          <span class="num" style="width:80px">{{ d.n }} 件 / {{ d.pct }}%</span>
        </div>
        <div class="desc" style="margin-top:12px">
          <div class="r"><div class="k">撤案合计</div><div class="v">— 件</div></div>
        </div>
      </div>

      <!-- 佣金支出聚合 -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>佣金支出聚合（本物业付出收佣）</div><div class="ops"><span class="note" style="margin:0">{{ scopeLabel[role] || '本物业' }}</span></div></div>
        <table>
          <thead><tr><th>批次</th><th>回款基数</th><th>收佣比例</th><th>佣金支出</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="r in (data?.rows ?? [])" :key="r.dimName">
              <td>{{ r.dimName }}</td>
              <td class="num">{{ yuan(r.dueCents) }}</td>
              <td>{{ r.commRate ? (r.commRate * 100).toFixed(1) + '%' : '—' }}</td>
              <td class="num">{{ yuan(r.commCents || (r.dueCents && r.commRate ? r.dueCents * r.commRate : undefined)) }}</td>
              <td><span class="tag" :class="r.settled ? 'suc' : 'war'">{{ r.settled ? '已结清' : '未结' }}</span></td>
            </tr>
            <tr v-if="!(data?.rows ?? []).length"><td colspan="5" class="note" style="text-align:center">暂无数据</td></tr>
          </tbody>
        </table>
        <div class="note">数据引自收佣对账口径；物业仅见本物业收佣支出，不可见平台付佣线。</div>
      </div>

      <!-- 能力用量汇总 -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>能力用量汇总（STT/短信/存证/法律）</div><div class="ops"><span class="note" style="margin:0">{{ scopeLabel[role] || '本物业' }} · 仅统计用量</span></div></div>
        <div class="kpis" style="grid-template-columns:repeat(4,1fr)">
          <div class="kpi" v-for="cap in capBilling" :key="cap.item">
            <div class="n">{{ cap.qty }}</div><div class="l">{{ cap.item }}</div>
          </div>
        </div>
        <div class="note">能力用量引自计费明细：STT 按分钟、短信按条、存证/法律按次；<b>本汇总仅统计用量、不展示金额</b>。</div>
      </div>

      <!-- 撤案统计 -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>撤案统计</div><div class="ops"><span class="note" style="margin:0">{{ scopeLabel[role] || '本物业' }}</span></div></div>
        <div class="sec-title">原因分布</div>
        <div class="note">暂无撤案数据</div>
      </div>
    </template>

    <!-- ═══ VL：服务商视角（催收员产能 / 佣金汇总 / 团队即时看板 / 批次汇总）═══ -->
    <template v-else-if="role === 'VL'">
      <!-- ① 催收员产能：/reports/operation?dimension=collector（按持有催收员聚合） -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>催收员产能</div><div class="ops"><span class="note" style="margin:0">按持有催收员 · 本服务商</span></div></div>
        <table>
          <thead><tr><th>催收员</th><th>案件数</th><th>回款额</th><th>回款率</th></tr></thead>
          <tbody>
            <tr v-for="r in coProd" :key="r.dimKey">
              <td>{{ r.dimName || '—' }}</td>
              <td class="num">{{ r.caseCount ?? 0 }}</td>
              <td class="num">{{ yuan(r.repayCents) }}</td>
              <td class="num">{{ pct(r.repayRate) }}</td>
            </tr>
            <tr v-if="!coProd.length"><td colspan="4" class="note" style="text-align:center">暂无持有案件的催收员</td></tr>
          </tbody>
        </table>
      </div>

      <!-- ② 佣金汇总：/co-commissions（应得/已付/未付，付给本商催收员） -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>佣金汇总（付本商催收员）</div><div class="ops"><span class="note" style="margin:0">应得/已付/未付</span></div></div>
        <table>
          <thead><tr><th>催收员</th><th>批次数</th><th>应得</th><th>已付</th><th>未付</th></tr></thead>
          <tbody>
            <tr v-for="c in coComm" :key="c.collectorId">
              <td>{{ c.name }}</td>
              <td class="num">{{ c.batchCount }}</td>
              <td class="num">{{ yuan(c.dueCents) }}</td>
              <td class="num"><span class="tag suc">{{ yuan(c.settledCents) }}</span></td>
              <td class="num"><span class="tag" :class="c.unsettledCents ? 'war' : 'inf'">{{ yuan(c.unsettledCents) }}</span></td>
            </tr>
            <tr v-if="!coComm.length"><td colspan="5" class="note" style="text-align:center">暂无佣金数据</td></tr>
          </tbody>
        </table>
        <div class="note">口径引自「催收员佣金」；付佣对象为本商催收员，与平台付佣线（平台付服务商）不同。</div>
      </div>

      <!-- ③ 团队即时看板（US-M10-03）：/providers/{id}/collector-capacity -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>团队即时看板（US-M10-03）</div><div class="ops"><span class="note" style="margin:0">本商全员 · 持有上限 {{ vlHoldCap }}</span></div></div>
        <table>
          <thead><tr><th>催收员</th><th>持有案件数</th><th>今日动作</th><th>容量余量</th><th>今日回款</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="m in vlTeam" :key="m.collectorId">
              <td>{{ m.name }}</td>
              <td class="num">{{ m.holding }}</td>
              <td class="num">{{ m.todayActions ?? 0 }}</td>
              <td><span class="tag" :class="m.remaining <= 0 ? 'dan' : (m.remaining <= 5 ? 'war' : 'suc')">余{{ m.remaining }}件</span></td>
              <td class="num">{{ m.todayRepayCents != null ? yuan(m.todayRepayCents) : '—' }}</td>
              <td><span class="tag" :class="m.remaining <= 0 ? 'dan' : 'suc'">{{ m.remaining <= 0 ? '满员' : '在线' }}</span></td>
            </tr>
            <tr v-if="!vlTeam.length"><td colspan="6" class="note" style="text-align:center">本商暂无在职催收员</td></tr>
          </tbody>
        </table>
      </div>

      <!-- ④ 批次汇总：/reports/operation?dimension=batch（默认 data） -->
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>批次汇总</div><div class="ops"><span class="note" style="margin:0">本服务商承接批次</span></div></div>
        <table>
          <thead><tr><th>批次</th><th>应收总额</th><th>案件数</th><th>回款率</th></tr></thead>
          <tbody>
            <tr v-for="r in (data?.rows ?? [])" :key="r.dimKey">
              <td>{{ r.dimName }}</td>
              <td class="num">{{ yuan(r.dueCents) }}</td>
              <td class="num">{{ r.caseCount ?? 0 }}</td>
              <td class="num">{{ pct(r.repayRate) }}</td>
            </tr>
            <tr v-if="!(data?.rows ?? []).length"><td colspan="4" class="note" style="text-align:center">暂无批次数据</td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
