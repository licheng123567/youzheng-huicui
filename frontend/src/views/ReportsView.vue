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
const reportArea = ref('')
const reportPeriod = ref<'month' | 'quarter' | 'year'>('month')
const reportRange = ref('')

const scopeLabel: Record<string, string> = { SA: '全局', SE: '范围内', PL: '本物业', PC: '本物业', VL: '本服务商', CO: '本人' }

async function load() {
  const { data: d, error } = await api.GET('/reports/operation', { params: { query: { dimension: 'batch', page: 1, size: 50 } } as any })
  if (error) { ElMessage.error('报表加载失败'); return }
  data.value = d
}

function applyFilter() { load(); loadVl() }

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

onMounted(() => { load(); loadVl() })
</script>

<template>
  <div>
    <!-- 工具栏 -->
    <div class="toolbar" style="margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <span class="note" style="margin:0">报表范围：{{ scopeLabel[role] || '全局' }}（导出仅含范围内数据）</span>
        <select class="inp" v-model="reportArea" style="min-width:120px"><option value="">全部区域</option></select>
        <select class="inp" v-model="reportPeriod" style="min-width:110px"><option value="month">按月</option><option value="quarter">按季</option><option value="year">按年</option></select>
        <input class="inp" type="month" v-model="reportRange" style="min-width:130px" />
        <button class="btn df sm" @click="applyFilter">应用筛选</button>
      </div>
      <button v-if="auth.has('report.export')" class="btn sm" @click="exportReport">导出</button>
    </div>

    <!-- KPI 卡片 -->
    <div class="kpis" v-if="data">
      <div class="kpi" v-for="s in kpis" :key="s.l">
        <div class="n">{{ s.n }}</div><div class="l">{{ s.l }}</div>
      </div>
    </div>

    <!-- ═══ SA/SE：平台视角 ═══ -->
    <template v-if="role === 'SA' || role === 'SE'">
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>区域 · 周期 损益聚合</div><div class="ops"><span class="note" style="margin:0">区域：{{ reportArea || '全部' }}</span></div></div>
        <table><thead><tr><th>区域</th><th>周期</th><th>佣金收入</th><th>佣金支出</th><th>能力收入</th><th>平台利润</th></tr></thead>
          <tbody><tr><td colspan="6" class="note" style="text-align:center">暂无数据</td></tr></tbody>
        </table>
        <div class="note">利润=（佣金收入−佣金支出）+能力收入；本期不计能力成本。</div>
      </div>
      <div class="card">
        <div class="card-h"><div class="t"><span class="bar"></span>佣金毛利</div></div>
        <table><thead><tr><th>项目</th><th>收佣</th><th>付佣</th><th>毛利</th><th>毛利率</th></tr></thead><tbody><tr><td colspan="5" class="note" style="text-align:center">暂无数据</td></tr></tbody></table>
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
