<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import { caseStatusLabel } from '../constants/enums'
import { yuan, pct } from '../utils/money'
import DsDrawer from '../components/DsDrawer.vue'
import SeaView from './SeaView.vue'
import * as XLSX from 'xlsx'

// GET /batches → BatchView(平台双线/物业只收佣/服务商只付佣)。SA 派单(M3)；物业可导入批次/作废(批次2)。
const auth = useAuth()
// 撮合派单与平台公海是同一批待派案件的两个视角（原型即同一模板）——合并为本页两个 Tab：
//   「批次派单」= 批次粒度首派/重派/开放费率/作废；「平台公海」= 案件粒度再派/开放抢单/竞争态（内嵌 SeaView 平台形态）。
// 仅平台(SA/SE)在裸 /batches 会看到 Tab；PL/PC/VL 只从 /batches/{id} 详情下钻进来，不涉及。
const isPlatform = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')
const platformTab = ref<'dispatch' | 'sea'>('dispatch')
// 资金双线列可见性(H-03)：收佣=平台/物业、付佣=平台/服务商，整列裁剪而非占位串。
const { showCommInRate, showPayOutRate, ratePct } = useRoleFields()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('')

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/batches', { params: { query: { page: 1, size: 20 } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}

// M3 派单/重派：WHOLE(整批) / SPLIT(拆分: splitBy=count 按件数 / cases 勾选具体案件→caseIds，US-M3-01)
const dlg = ref(false)
const dispCases = ref<any[]>([]); const caseSel = ref<any[]>([])
const form = ref<any>({ batchId: '', providerId: '', payOutRate: 0.2, mode: 'WHOLE', splitBy: 'count', splitCount: 10, redispatch: false })
// 重派软警示：上一承接段（服务商/结项原因）——结项后重派对象含派回原商由平台裁量，仅提示不拦截（v1.17.0）
const lastSeg = ref<any>(null)
function openDispatch(id: string, redispatch = false) {
  form.value = { batchId: id, providerId: '', payOutRate: 0.2, mode: 'WHOLE', splitBy: 'count', splitCount: 10, redispatch }
  dispCases.value = []; caseSel.value = []; lastSeg.value = null; dlg.value = true
  if (redispatch) loadLastSegment(id)
}
async function loadLastSegment(batchId: string) {
  const { data } = await api.GET('/batches/{id}/engagements', { params: { path: { id: batchId } } })
  const items = (data as any)?.items ?? []
  lastSeg.value = items.length ? items[items.length - 1] : null
}

// ── v1.17.0 结项（终止当前服务商承接·全部收回+承诺保留）──
const REASON_LABEL: Record<string, string> = {
  INCAPABLE: '无力催收', COOP_TERMINATED: '合作终止', PROPERTY_REQUEST: '物业要求', OTHER: '其他',
}
const ceDlg = ref(false); const ceBatch = ref<any>(null); const cePreview = ref<any>(null)
const ceForm = ref<any>({ reason: 'INCAPABLE', note: '' }); const ceLoading = ref(false); const ceSaving = ref(false)
async function openCloseEngagement(row: any) {
  ceBatch.value = row; cePreview.value = null; ceForm.value = { reason: 'INCAPABLE', note: '' }
  ceDlg.value = true; ceLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/close-preview', { params: { path: { id: row.id } } })
  ceLoading.value = false
  if (error) { ElMessage.error('结项预览加载失败：' + ((error as any)?.message ?? '')); ceDlg.value = false; return }
  cePreview.value = data
}
async function submitCloseEngagement() {
  if (!ceForm.value.note?.trim()) { ElMessage.warning('结项备注必填（留痕）'); return }
  ceSaving.value = true
  const { data, error } = await api.POST('/batches/{id}/close-engagement', {
    params: { path: { id: ceBatch.value.id } },
    body: { reason: ceForm.value.reason, note: ceForm.value.note.trim() } as any,
  })
  ceSaving.value = false
  if (error) { ElMessage.error('结项失败：' + ((error as any)?.message ?? '')); return }
  const r = data as any
  ElMessage.success(`已结项：收回 ${r?.recalled ?? 0} 件回平台公海（承诺保留），批次可重派`)
  ceDlg.value = false; load()
}

// ── v1.17.0 承接历史抽屉 ──
const ehDlg = ref(false); const ehBatch = ref<any>(null); const ehItems = ref<any[]>([]); const ehLoading = ref(false)
async function openEngagements(row: any) {
  ehBatch.value = row; ehItems.value = []; ehDlg.value = true; ehLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/engagements', { params: { path: { id: row.id } } })
  ehLoading.value = false
  if (error) { ElMessage.error('承接历史加载失败'); return }
  ehItems.value = (data as any)?.items ?? []
}
const dt = (s?: string | null) => (s ? s.slice(0, 10) : '至今')
// 派单决策辅助：服务商客观经营指标(BR-M3-24)
const metrics = ref<any[]>([])
async function loadMetrics() {
  const { data, error } = await api.GET('/dispatch/provider-metrics', {})
  if (error) { ElMessage.error('加载服务商指标失败（需 case.dispatch）'); return }
  metrics.value = (data as any)?.items ?? []
}
async function loadDispatchCases() {
  const { data } = await api.GET('/cases', { params: { query: { batchId: form.value.batchId, page: 1, size: 200 } } as any })
  dispCases.value = (data as any)?.items ?? []
  if (!dispCases.value.length) ElMessage.info('该批次暂无可派案件')
}
async function submitDispatch() {
  if (!form.value.providerId) { ElMessage.warning('请填服务商 org id'); return }
  const body: any = { mode: form.value.mode, providerId: form.value.providerId, payOutRate: form.value.payOutRate }
  if (form.value.mode === 'SPLIT') {
    if (form.value.splitBy === 'cases') {
      if (!caseSel.value.length) { ElMessage.warning('请勾选案件'); return }
      body.caseIds = caseSel.value.map((c) => String(c.id))   // caseIds 优先(D3)
    } else body.splitCount = form.value.splitCount
  }
  acting.value = form.value.batchId
  const ep = form.value.redispatch ? '/batches/{id}/redispatch' : '/batches/{id}/dispatch'
  const { error } = await api.POST(ep as any, { params: { path: { id: form.value.batchId } }, body })
  acting.value = ''; dlg.value = false
  if (error) { ElMessage.error((form.value.redispatch ? '重派' : '派单') + '失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(form.value.redispatch ? '已重派' : '已派单'); load()
}
// 开放抢单费率：PUT /batches/{id}/open-rate
async function setOpenRate(row: any) {
  try {
    const { value } = await ElMessageBox.prompt('开放抢单费率(分数 0-1，如 0.18=18%)', '设置开放费率 ' + row.code, { inputValidator: (v) => (Number(v) >= 0 && Number(v) <= 1) || '须 0-1 分数' })
    const { error } = await api.PUT('/batches/{id}/open-rate', { params: { path: { id: row.id } }, body: { openRate: Number(value) } as any })
    if (error) { ElMessage.error('设置失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已设开放费率（案件入开放抢单池）'); load()
  } catch { /* 取消 */ }
}

// ── 批次导入向导（3 步：① 填信息 + 逐条录入 → ② 提交校验 → ③ 查看结果）──
const impDlg = ref(false)
const impStep = ref(0) // 0=录入, 1=校验中, 2=结果
const importProjects = ref<any[]>([])
const emptyRow = () => ({ acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, penaltyYuan: null as number | null, periodFrom: '', periodTo: '', idCard: '', addr: '' })
const imp = ref<any>({ projectId: '', commInRate: 0.1, rows: [emptyRow()] })
const impResult = ref<any>(null)
const impSaving = ref(false)

// 欠费月数计算
function calcMonths(from: string, to: string): string {
  if (!from || !to) return ''
  const f = new Date(from), t = new Date(to)
  if (isNaN(f.getTime()) || isNaN(t.getTime()) || f >= t) return ''
  const days = Math.round((t.getTime() - f.getTime()) / 86400000)
  const months = Math.floor(days / 30)
  if (months < 1) return `${days}天`
  const remDays = days % 30
  return remDays > 0 ? `${months}个月${remDays}天` : `${months}个月`
}

// ── Excel 导入解析 + 校验 ──
const excelFile = ref<File | null>(null)
const excelErrors = ref<{ row: number; msg: string }[]>([])

function handleExcelUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  excelFile.value = file
  excelErrors.value = []

  const reader = new FileReader()
  reader.onload = (ev) => {
    try {
      const wb = XLSX.read(ev.target?.result, { type: 'binary' })
      const ws = wb.Sheets[wb.SheetNames[0]]
      const data = XLSX.utils.sheet_to_json<any[]>(ws, { header: 1 })
      if (data.length < 2) { excelErrors.value.push({ row: 0, msg: 'Excel 至少需要表头+1行数据' }); return }

      // 第一行为表头，从第二行开始解析
      const rows: any[] = []; const errs: { row: number; msg: string }[] = []
      for (let i = 1; i < data.length; i++) {
        const r = data[i]
        if (!r || r.every((c: any) => !c)) continue // 跳过空行
        const row: any = {
          acctNo: String(r[0] ?? '').trim(),
          ownerName: String(r[1] ?? '').trim(),
          phone: String(r[2] ?? '').trim(),
          room: String(r[3] ?? '').trim(),
          dueYuan: parseFloat(String(r[4] ?? '0').replace(/[¥,]/g, '')) || 0,
          penaltyYuan: r[5] != null && String(r[5]).trim() !== '' ? (parseFloat(String(r[5]).replace(/[¥,]/g, '')) || 0) : null,
          periodFrom: String(r[6] ?? '').trim(),
          periodTo: String(r[7] ?? '').trim(),
          idCard: String(r[8] ?? '').trim(),
          addr: String(r[9] ?? '').trim(),
        }
        // 必填校验
        if (!row.acctNo || !row.ownerName || !row.phone || !row.room) { errs.push({ row: i + 1, msg: `必填项缺失（户号/姓名/手机/房号）` }); continue }
        // 手机校验
        if (!/^1\d{10}$/.test(row.phone)) { errs.push({ row: i + 1, msg: `手机号 "${row.phone}" 须为 11 位` }); continue }
        // 身份证校验(选填但有值则校验)
        if (row.idCard && !/^\d{17}[\dXx]$/.test(row.idCard)) { errs.push({ row: i + 1, msg: `身份证 "${row.idCard}" 须为 18 位` }); continue }
        rows.push(row)
      }

      excelErrors.value = errs
      if (rows.length) {
        // 替换现有行（保留已有手动录入吗？直接追加）
        const existing = imp.value.rows.filter((r: any) => r.acctNo || r.ownerName || r.phone)
        imp.value.rows = [...existing, ...rows]
        ElMessage.success(`Excel 解析完成：成功 ${rows.length} 条${errs.length ? '，跳过 ' + errs.length + ' 条（见下方）' : ''}`)
      }
    } catch {
      excelErrors.value = [{ row: 0, msg: 'Excel 文件解析失败，请检查格式（第一行为表头：户号/姓名/手机/房号/应收/滞纳金/欠费起/欠费止/身份证/地址）' }]
    }
  }
  reader.readAsBinaryString(file)
}

async function openImport() {
  imp.value = { projectId: '', commInRate: 0.1, rows: [emptyRow()] }
  impResult.value = null; impStep.value = 0; impSaving.value = false
  excelFile.value = null; excelErrors.value = []
  // 加载项目列表
  const { data } = await api.GET('/projects', { params: { query: { page: 1, size: 200 } } as any })
  importProjects.value = (data as any)?.items ?? []
  impDlg.value = true
}

async function submitImport() {
  if (!imp.value.projectId) { ElMessage.warning('请选择项目'); return }
  impStep.value = 1; impSaving.value = true
  const rows = imp.value.rows
    .filter((r: any) => r.acctNo && r.ownerName && r.phone && r.room)
    .map((r: any) => {
    const period = r.periodFrom && r.periodTo ? `${r.periodFrom}~${r.periodTo}` : ''
    const row: any = { acctNo: r.acctNo, ownerName: r.ownerName, phone: r.phone, room: r.room, dueCents: Math.round(r.dueYuan * 100), arrearPeriod: period }
    if (r.penaltyYuan != null && r.penaltyYuan > 0) row.penaltyCents = Math.round(r.penaltyYuan * 100)
    const idCard = (r.idCard || '').trim(); const addr = (r.addr || '').trim()
    if (idCard || addr) row.litigation = { ...(idCard ? { idCard } : {}), ...(addr ? { addr } : {}) }
    return row
  })
  if (!rows.length) { ElMessage.warning('至少录入一条有效案件（户号+姓名+手机+房号必填）'); impStep.value = 0; impSaving.value = false; return }
  const { data, error } = await api.POST('/batches/import', { body: { projectId: String(imp.value.projectId), commInRate: Number(imp.value.commInRate), rows } as any })
  impSaving.value = false
  if (error) { ElMessage.error('导入失败：' + ((error as any)?.message ?? '')); impStep.value = 0; return }
  impResult.value = data as any; impStep.value = 2
  const result = impResult.value
  if ((result?.skipped ?? 0) === 0 && (!result?.errors || result.errors.length === 0)) {
    ElMessage.success(`导入完成：成功 ${result.succeeded}（共 ${result.total}）`)
  } else {
    ElMessage.warning(`导入完成：成功 ${result.succeeded} / 跳过 ${result.skipped}（共 ${result.total}）`)
  }
}

function closeImport() { impDlg.value = false; load() }

// 批次2 作废：POST /batches/{id}/void（留痕）
async function voidBatch(row: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt('作废原因（留痕，不可逆）', '作废批次 ' + row.code, { inputValidator: (v) => !!v || '原因必填' })
    const { error } = await api.POST('/batches/{id}/void', { params: { path: { id: row.id } }, body: { reason } as any })
    if (error) { ElMessage.error('作废失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已作废'); load()
  } catch { /* 取消 */ }
}
// 纯展示辅助：批次状态 → ds-admin .tag 配色（不改数据，仅 UI 着色）
const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', DISPATCHED: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf', OPEN_POOL: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

const route = useRoute()
onMounted(() => { load(); if (route.query.openImport === '1') openImport() })
</script>

<template>
  <div>
    <!-- 平台侧 Tab：批次派单 / 平台公海（合并原独立「平台公海」菜单 BR-M3-01/29） -->
    <div v-if="isPlatform" class="segctrl" style="margin-bottom:12px">
      <span :class="{ on: platformTab === 'dispatch' }" @click="platformTab = 'dispatch'">批次运营</span>
      <span :class="{ on: platformTab === 'sea' }" @click="platformTab = 'sea'">平台公海</span>
    </div>

    <!-- 平台公海：内嵌 SeaView（其自身按 isPlatformSide 渲染 平台公海+开放抢单池，含再派/开放抢单/事件流水） -->
    <SeaView v-if="isPlatform && platformTab === 'sea'" />

  <div class="card" v-show="!isPlatform || platformTab === 'dispatch'">
    <div class="card-h">
      <div class="t"><span class="bar"></span>批次（催收单）</div>
      <div class="ops">
        <span class="note" style="margin:0">批次列表 · 共 {{ total }}</span>
        <button v-if="auth.has('batch.import') || auth.has('proj.edit')" class="btn sm" @click="openImport">+ 导入批次</button>
      </div>
    </div>

    <!-- v1.17.0 批次运营表：项目/户数/应收/已收/回款率/状态分布/服务商/承接段 —— GET /batches 契约 BatchBase 新字段 -->
    <table v-loading="loading">
      <thead>
        <tr>
          <th>批次号</th>
          <th>项目</th>
          <th style="width:64px">户数</th>
          <th>应收</th>
          <th>已收</th>
          <th style="width:80px">回款率</th>
          <th style="width:130px">状态分布</th>
          <th>服务商</th>
          <th v-if="showCommInRate" style="width:88px">收佣比例</th>
          <th v-if="showPayOutRate" style="width:88px">付佣比例</th>
          <th style="width:96px">状态</th>
          <th style="width:330px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td><a class="link" @click="$router.push(`/batches/${row.id}`)">{{ row.code }}</a></td>
          <td>{{ row.projectName }}</td>
          <td class="num">{{ row.caseCount ?? '—' }}</td>
          <td class="num">{{ yuan(row.dueTotalCents) }}</td>
          <td class="num">{{ yuan(row.repaidTotalCents) }}</td>
          <td class="num">{{ pct(row.repayRate) }}</td>
          <td>
            <!-- 迷你状态分布：待派/待接/商公海/在催/开放/结清 -->
            <span v-if="row.poolDist" class="pooldist" :title="`待派${row.poolDist.s0} 待接${row.poolDist.s1} 商公海${row.poolDist.s2} 在催${row.poolDist.s3} 开放${row.poolDist.s4} 结清${row.poolDist.settled} 关闭${row.poolDist.closed}`">
              <span v-if="row.poolDist.s0" class="tag inf">待派{{ row.poolDist.s0 }}</span>
              <span v-if="row.poolDist.s1 + row.poolDist.s2" class="tag war">在商{{ row.poolDist.s1 + row.poolDist.s2 }}</span>
              <span v-if="row.poolDist.s3" class="tag pri">在催{{ row.poolDist.s3 }}</span>
              <span v-if="row.poolDist.settled" class="tag suc">结清{{ row.poolDist.settled }}</span>
            </span>
            <span v-else>—</span>
          </td>
          <td>
            <span v-if="row.providerName">{{ row.providerName }}</span>
            <span v-else class="tag inf">待派单</span>
            <sup v-if="(row.engagementCount ?? 0) > 1" class="tag war" style="margin-left:4px" title="发生过结项重派，点「承接历史」看各段表现">{{ row.engagementCount }}任</sup>
          </td>
          <!-- 收佣比例：仅平台/物业视角整列渲染(服务商视角字段级无→整列不出 H-03) -->
          <td v-if="showCommInRate" class="num">{{ ratePct(row.commInRate) }}</td>
          <!-- 付佣比例：仅平台/服务商视角整列渲染(物业视角字段级无→整列不出，不显占位串 H-03) -->
          <td v-if="showPayOutRate" class="num">{{ ratePct(row.payOutRate) }}</td>
          <td><span class="tag" :class="statusTag(row.status)" :title="row.status">{{ caseStatusLabel(row.status) }}</span></td>
          <td>
            <a v-if="auth.has('case.dispatch') && !row.providerId" class="btn txt" :class="{ 'is-disabled': acting===row.id }" @click="acting===row.id || openDispatch(row.id)">派单</a>
            <a v-if="auth.has('case.dispatch')" class="btn txt" @click="openDispatch(row.id, true)">重派</a>
            <a v-if="auth.has('case.dispatch') && row.providerId" class="btn txt dgc" @click="openCloseEngagement(row)">结项</a>
            <a v-if="auth.has('case.dispatch')" class="btn txt" @click="setOpenRate(row)">开放费率</a>
            <a v-if="isPlatform && (row.engagementCount ?? 0) > 0" class="btn txt" @click="openEngagements(row)">承接历史</a>
            <a v-if="auth.has('case.void')" class="btn txt dgc" @click="voidBatch(row)">作废</a>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td :colspan="10 + (showCommInRate ? 1 : 0) + (showPayOutRate ? 1 : 0)" style="text-align:center;color:var(--sec);padding:32px 0">暂无批次，点击「+ 导入批次」导入催收单。</td>
        </tr>
      </tbody>
    </table>

    <!-- 派单/重派 -->
    <DsDrawer v-model="dlg" :title="(form.redispatch?'重派':'派单')" :width="640">
      <!-- v1.17.0 重派软警示：上一承接段（结项后重派对象含派回原商由平台裁量，仅提示不拦截） -->
      <div v-if="form.redispatch && lastSeg" class="alert warn" style="margin-bottom:10px">
        上一承接：<b>{{ lastSeg.providerName }}</b>（第 {{ lastSeg.seq }} 任 · {{ dt(lastSeg.startedAt) }} ~ {{ dt(lastSeg.endedAt) }}）
        <template v-if="lastSeg.endReason">· 结项原因：{{ REASON_LABEL[lastSeg.endReason] ?? lastSeg.endReason }}</template>
        <template v-if="lastSeg.periodRepayRate != null">· 期间回款率 {{ pct(lastSeg.periodRepayRate) }}</template>
      </div>
      <el-form label-width="120px">
        <el-form-item label="方式"><el-radio-group v-model="form.mode"><el-radio-button label="WHOLE">整批</el-radio-button><el-radio-button label="SPLIT">拆分</el-radio-button></el-radio-group></el-form-item>
        <template v-if="form.mode==='SPLIT'">
          <el-form-item label="拆分依据"><el-radio-group v-model="form.splitBy"><el-radio-button label="count">按件数</el-radio-button><el-radio-button label="cases">勾选案件</el-radio-button></el-radio-group></el-form-item>
          <el-form-item v-if="form.splitBy==='count'" label="拆分件数"><el-input-number v-model="form.splitCount" :min="1" /><span style="margin-left:8px;color:#909399">按入池序选 N 个(D3)</span></el-form-item>
          <el-form-item v-else label="勾选案件">
            <el-button size="small" @click="loadDispatchCases">加载本批案件</el-button>
            <el-table :data="dispCases" border size="small" max-height="240" style="margin-top:6px" @selection-change="(v:any)=>caseSel=v">
              <el-table-column type="selection" width="40" />
              <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
              <el-table-column label="状态"><template #default="{row}"><span :title="row.status">{{ caseStatusLabel(row.status) }}</span></template></el-table-column><el-table-column prop="acctNo" label="户号" />
            </el-table>
            <span style="color:#606266">已选 {{ caseSel.length }} 件（US-M3-01 同批部分案件派不同服务商）</span>
          </el-form-item>
        </template>
        <el-form-item label="服务商指标">
          <el-button size="small" @click="loadMetrics">加载各服务商指标（决策辅助 BR-M3-24）</el-button>
          <el-table v-if="metrics.length" :data="metrics" border size="small" style="margin-top:6px;cursor:pointer" @row-click="(r:any)=>form.providerId=r.providerId">
            <el-table-column prop="providerName" label="服务商" />
            <el-table-column label="在催"><template #default="{row}">{{ row.activeCases }}</template></el-table-column>
            <el-table-column label="催收员"><template #default="{row}">{{ row.collectorCount }}</template></el-table-column>
            <el-table-column label="人均持仓"><template #default="{row}">{{ row.avgHolding?.toFixed(1) }}</template></el-table-column>
            <el-table-column label="近30天回款率"><template #default="{row}">{{ row.recentRepayRate!=null?(row.recentRepayRate*100).toFixed(1)+'%':'—' }}</template></el-table-column>
          </el-table>
          <span style="color:#909399;font-size:12px">仅客观指标陈列，不评分/不加权（BR-M3-24）。点行填服务商 id。</span>
        </el-form-item>
        <el-form-item label="服务商 org id"><el-input v-model="form.providerId" placeholder="点上表行或手填" /></el-form-item>
        <el-form-item label="付佣比例(小数)"><el-input-number v-model="form.payOutRate" :min="0" :max="1" :step="0.01" /><span style="margin-left:8px;color:#909399">0.2=20%（须≤收佣，防倒挂）</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="acting===form.batchId" @click="submitDispatch">{{ form.redispatch?'重派':(form.mode==='SPLIT'?'拆分派单':'整批派单') }}</el-button></template>
    </DsDrawer>

    <!-- v1.17.0 结项确认（终止当前服务商承接·全部收回+承诺保留） -->
    <DsDrawer v-model="ceDlg" :title="`结项 · ${ceBatch?.code ?? ''}（终止服务商承接）`" :width="560">
      <div v-loading="ceLoading">
        <template v-if="cePreview">
          <div class="alert info" style="margin-bottom:10px">
            将终止 <b>{{ cePreview.providerName }}</b> 对本批次的承接：收回其名下全部在催案件回<b>平台公海</b>，
            批次回「待派单」可重派。已催回的回款与应得付佣按到账时点归属，<b>不受结项影响</b>。
          </div>
          <el-descriptions :column="4" border size="small" style="margin-bottom:10px">
            <el-descriptions-item label="待接单">{{ cePreview.recallable?.s1 ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="商公海">{{ cePreview.recallable?.s2 ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="催收中">{{ cePreview.recallable?.s3 ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="合计收回">{{ cePreview.recallable?.total ?? 0 }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="cePreview.promisedCases?.length" class="alert danger" style="margin-bottom:8px">
            <b>承诺警示：</b>以下 {{ cePreview.promisedCases.length }} 件带有效分期承诺，将一并收回——
            承诺与跟进记录随案保留，重派后新服务商可见完整历史。
          </div>
          <el-table v-if="cePreview.promisedCases?.length" :data="cePreview.promisedCases" border size="small" max-height="200" style="margin-bottom:10px">
            <el-table-column prop="ownerName" label="业主" width="100" />
            <el-table-column prop="room" label="房号" width="90" />
            <el-table-column prop="pendingInstallments" label="未兑期数" width="90" />
            <el-table-column prop="nextDueDate" label="最近到期" />
          </el-table>
          <el-form label-width="90px">
            <el-form-item label="结项原因" required>
              <el-select v-model="ceForm.reason" style="width:220px">
                <el-option v-for="(l, k) in REASON_LABEL" :key="k" :label="l" :value="k" />
              </el-select>
            </el-form-item>
            <el-form-item label="备注" required>
              <el-input v-model="ceForm.note" type="textarea" :rows="2" placeholder="结项备注（必填留痕，如：三个月回款率不足 5%，协商终止）" />
            </el-form-item>
          </el-form>
        </template>
      </div>
      <template #footer>
        <el-button @click="ceDlg = false">取消</el-button>
        <el-button type="danger" :loading="ceSaving" :disabled="!cePreview" @click="submitCloseEngagement">确认结项并收回</el-button>
      </template>
    </DsDrawer>

    <!-- v1.17.0 承接历史（每段 服务商/起止/期间回款/期间回款率/结项原因） -->
    <DsDrawer v-model="ehDlg" :title="`承接历史 · ${ehBatch?.code ?? ''}`" :width="640">
      <div v-loading="ehLoading">
        <div v-for="seg in ehItems" :key="seg.id" class="card" style="margin-bottom:10px;padding:12px">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <b>第 {{ seg.seq }} 任 · {{ seg.providerName }}</b>
            <span class="tag" :class="seg.endedAt ? 'inf' : 'suc'">{{ seg.endedAt ? '已结项' : '承接中' }}</span>
          </div>
          <div class="note" style="margin:6px 0">{{ dt(seg.startedAt) }} ~ {{ dt(seg.endedAt) }} · 付佣快照 {{ pct(seg.payOutRate) }}</div>
          <div style="display:flex;gap:18px;font-size:13px">
            <span>期间回款 <b>{{ yuan(seg.periodRepayCents) }}</b></span>
            <span>期初剩余应收 {{ yuan(seg.openingDueCents) }}</span>
            <span>期间回款率 <b>{{ pct(seg.periodRepayRate) }}</b></span>
          </div>
          <div v-if="seg.endReason" class="note" style="margin-top:6px">
            结项：{{ REASON_LABEL[seg.endReason] ?? seg.endReason }}<template v-if="seg.endNote">·{{ seg.endNote }}</template>
            <template v-if="seg.endedByName">（{{ seg.endedByName }}）</template>
          </div>
        </div>
        <div v-if="!ehLoading && !ehItems.length" class="note" style="text-align:center;padding:20px">暂无承接记录（批次尚未派单）</div>
      </div>
      <template #footer><el-button @click="ehDlg = false">关闭</el-button></template>
    </DsDrawer>

    <!-- 导入批次向导（3 步：对标原型 view==='import'） -->
    <DsDrawer v-model="impDlg" title="批次导入向导" :width="820" @closed="impStep===2 && closeImport()">
      <!-- 步骤条 -->
      <div class="steps" style="display:flex;align-items:center;gap:0;margin-bottom:18px">
        <div style="display:flex;align-items:center;gap:6px;font-size:13px" :style="{color: impStep>0 ? 'var(--success)' : impStep===0 ? 'var(--primary)' : 'var(--sec)'}">
          <span style="width:24px;height:24px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;border:2px solid currentColor">{{ impStep > 0 ? '✓' : '1' }}</span> 录入案件
        </div>
        <div style="flex:1;height:2px;margin:0 10px" :style="{background: impStep>0 ? 'var(--success)' : 'var(--bd2)'}"></div>
        <div style="display:flex;align-items:center;gap:6px;font-size:13px" :style="{color: impStep===1 ? 'var(--primary)' : impStep>1 ? 'var(--success)' : 'var(--sec)'}">
          <span style="width:24px;height:24px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;border:2px solid currentColor">{{ impStep > 1 ? '✓' : '2' }}</span> 提交校验
        </div>
        <div style="flex:1;height:2px;margin:0 10px" :style="{background: impStep>1 ? 'var(--success)' : 'var(--bd2)'}"></div>
        <div style="display:flex;align-items:center;gap:6px;font-size:13px" :style="{color: impStep===2 ? 'var(--primary)' : 'var(--sec)'}">
          <span style="width:24px;height:24px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;border:2px solid currentColor">3</span> 查看结果
        </div>
      </div>

      <!-- Step 0: 录入案件 -->
      <template v-if="impStep === 0">
        <div class="search" style="margin-bottom:12px">
          <div class="fi"><span>导入到项目</span>
            <select class="inp" v-model="imp.projectId" style="min-width:180px">
              <option value="">选择项目</option>
              <option v-for="p in importProjects" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
            </select>
          </div>
          <div class="fi"><span>收佣比例(%)</span><input class="inp" type="number" v-model.number="imp.commInRate" style="min-width:80px" :min="1" :max="100" /></div>
          <span class="note" style="margin:0">默认 10%（项目继承），可逐批覆盖</span>
        </div>

        <div class="alert info" style="margin-bottom:10px">
          必填：户号 / 业主姓名 / 手机 / 应收金额。选填：房号 / 欠费期间 / 身份证号 / 地址（诉讼要素可后补）。
          支持上传 Excel 批量导入（第一行为表头：户号 / 姓名 / 手机 / 房号 / 应收 / 欠费起 / 欠费止 / 身份证 / 地址）。
        </div>

        <!-- Excel 上传区 -->
        <div style="margin-bottom:12px;display:flex;align-items:center;gap:12px">
          <input type="file" accept=".xlsx,.xls" style="display:none" id="excelInput" @change="handleExcelUpload" />
          <label for="excelInput" style="cursor:pointer;border:1px dashed var(--bd2);border-radius:6px;padding:8px 16px;font-size:13px;color:var(--primary);display:flex;align-items:center;gap:6px">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/></svg>
            上传 Excel 批量导入
          </label>
          <span v-if="excelFile" style="font-size:12px;color:var(--sec)">{{ excelFile.name }}</span>
        </div>

        <!-- Excel 校验错误 -->
        <div v-if="excelErrors.length" class="alert warn" style="margin-bottom:10px">
          校验结果：跳过 {{ excelErrors.length }} 行（手机号/身份证/必填项校验未通过）
          <div style="font-size:12px;margin-top:4px" v-for="e in excelErrors.slice(0, 5)" :key="e.row">第 {{ e.row }} 行：{{ e.msg }}</div>
          <div v-if="excelErrors.length > 5" style="font-size:12px;color:var(--sec)">…还有 {{ excelErrors.length - 5 }} 条错误</div>
        </div>

        <el-table :data="imp.rows" border size="small">
          <el-table-column label="户号" width="100"><template #default="{row}"><el-input v-model="row.acctNo" size="small" placeholder="必填" /></template></el-table-column>
          <el-table-column label="姓名" width="80"><template #default="{row}"><el-input v-model="row.ownerName" size="small" placeholder="必填" /></template></el-table-column>
          <el-table-column label="手机" width="120"><template #default="{row}"><el-input v-model="row.phone" size="small" placeholder="必填" /></template></el-table-column>
          <el-table-column label="房号" width="90"><template #default="{row}"><el-input v-model="row.room" size="small" placeholder="必填" /></template></el-table-column>
          <el-table-column label="应收(元)" width="110"><template #default="{row}"><el-input-number v-model="row.dueYuan" size="small" :min="0" :controls="false" style="width:90px" /></template></el-table-column>
          <el-table-column label="滞纳金(元)" width="110"><template #default="{row}"><el-input-number v-model="row.penaltyYuan" size="small" :min="0" :controls="false" style="width:90px" placeholder="选填" /></template></el-table-column>
          <el-table-column label="欠费起" width="120"><template #default="{row}"><el-date-picker v-model="row.periodFrom" type="month" value-format="YYYY-MM" placeholder="起始月" size="small" style="width:100%" /></template></el-table-column>
          <el-table-column label="欠费止" width="120"><template #default="{row}"><el-date-picker v-model="row.periodTo" type="month" value-format="YYYY-MM" placeholder="截止月" size="small" style="width:100%" /></template></el-table-column>
          <el-table-column label="时长" width="110"><template #default="{row}"><span style="font-size:12px;color:var(--primary)">{{ calcMonths(row.periodFrom, row.periodTo) || '—' }}</span></template></el-table-column>
          <el-table-column label="身份证(选填)" width="150"><template #default="{row}"><el-input v-model="row.idCard" size="small" placeholder="诉讼要素" /></template></el-table-column>
          <el-table-column label="地址(选填)" min-width="120"><template #default="{row}"><el-input v-model="row.addr" size="small" placeholder="诉讼要素" /></template></el-table-column>
          <el-table-column width="45"><template #default="{$index}"><el-button size="small" text type="danger" :disabled="imp.rows.length<=1" @click="imp.rows.splice($index,1)">×</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" text type="primary" style="margin-top:6px" @click="imp.rows.push(emptyRow())">+ 添加行</el-button>
      </template>

      <!-- Step 1: 提交中 -->
      <template v-if="impStep === 1">
        <div style="text-align:center;padding:32px">
          <el-icon class="is-loading" style="font-size:32px;color:var(--primary)"><svg viewBox="0 0 24 24" width="32" height="32"><circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="32" stroke-linecap="round"><animateTransform attributeName="transform" type="rotate" from="0 12 12" to="360 12 12" dur="1s" repeatCount="indefinite"/></circle></svg></el-icon>
          <div style="margin-top:12px;color:var(--sec)">正在提交校验，请稍候…</div>
        </div>
      </template>

      <!-- Step 2: 查看结果 -->
      <template v-if="impStep === 2 && impResult">
        <div :class="(impResult.skipped===0 && (!impResult.errors||impResult.errors.length===0)) ? 'alert ok' : 'alert warn'">
          {{ impResult.skipped===0 && (!impResult.errors||impResult.errors.length===0) ? '✅ 全部导入成功' : `⚠ 校验结果：共 ${impResult.total} 行，成功 ${impResult.succeeded} 行，跳过 ${impResult.skipped} 行` }}
        </div>
        <template v-if="impResult.errors && impResult.errors.length > 0">
          <div style="font-size:13px;color:#E6A23C;margin:10px 0 6px">错误明细（以下行已跳过）</div>
          <el-table :data="impResult.errors" border size="small" max-height="200">
            <el-table-column prop="row" label="行号" width="60" />
            <el-table-column prop="field" label="字段" width="100" />
            <el-table-column prop="code" label="错误码" width="120" />
            <el-table-column prop="message" label="消息" show-overflow-tooltip />
          </el-table>
        </template>
        <div v-if="impResult.batch" style="margin-top:12px;display:flex;gap:12px">
          <el-tag type="success">批次 {{ impResult.batch.code || impResult.batch.id }}</el-tag>
          <el-tag>成功 {{ impResult.succeeded }} 条</el-tag>
        </div>
      </template>

      <template #footer>
        <template v-if="impStep === 0">
          <el-button @click="impDlg = false">取消</el-button>
          <el-button type="primary" :disabled="!imp.projectId" @click="submitImport">下一步：提交校验</el-button>
        </template>
        <template v-if="impStep === 1">
          <el-button disabled>处理中…</el-button>
        </template>
        <template v-if="impStep === 2">
          <el-button @click="impDlg = false; load()">关闭</el-button>
          <el-button v-if="impResult && (impResult.skipped > 0 || (impResult.errors && impResult.errors.length > 0))" type="primary" @click="impStep = 0; impResult = null">返回修改</el-button>
        </template>
      </template>
    </DsDrawer>
  </div>
  </div>
</template>

<style scoped>
/* 派单/导入弹窗内嵌 EL 表格保持原生主题，不受本页 ds-admin 原生 table 规则影响 */
.btn.txt.is-disabled { opacity: .5; cursor: not-allowed; }
</style>
