<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import { caseStatusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'
import * as XLSX from 'xlsx'

// GET /batches → BatchView(平台双线/物业只收佣/服务商只付佣)。SA 派单(M3)；物业可导入批次/作废(批次2)。
const auth = useAuth()
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
function openDispatch(id: string, redispatch = false) {
  form.value = { batchId: id, providerId: '', payOutRate: 0.2, mode: 'WHOLE', splitBy: 'count', splitCount: 10, redispatch }
  dispCases.value = []; caseSel.value = []; dlg.value = true
}
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
const emptyRow = () => ({ acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, periodFrom: '', periodTo: '', idCard: '', addr: '' })
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
          periodFrom: String(r[5] ?? '').trim(),
          periodTo: String(r[6] ?? '').trim(),
          idCard: String(r[7] ?? '').trim(),
          addr: String(r[8] ?? '').trim(),
        }
        // 必填校验
        if (!row.acctNo || !row.ownerName || !row.phone) { errs.push({ row: i + 1, msg: `必填项缺失（户号/姓名/手机）` }); continue }
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
      excelErrors.value = [{ row: 0, msg: 'Excel 文件解析失败，请检查格式（第一行为表头：户号/姓名/手机/房号/应收/欠费起/欠费止/身份证/地址）' }]
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
    .filter((r: any) => r.acctNo && r.ownerName && r.phone)
    .map((r: any) => {
    const period = r.periodFrom && r.periodTo ? `${r.periodFrom}~${r.periodTo}` : ''
    const row: any = { acctNo: r.acctNo, ownerName: r.ownerName, phone: r.phone, room: r.room, dueCents: Math.round(r.dueYuan * 100), arrearPeriod: period }
    const idCard = (r.idCard || '').trim(); const addr = (r.addr || '').trim()
    if (idCard || addr) row.litigation = { ...(idCard ? { idCard } : {}), ...(addr ? { addr } : {}) }
    return row
  })
  if (!rows.length) { ElMessage.warning('至少录入一条有效案件（户号+姓名+手机必填）'); impStep.value = 0; impSaving.value = false; return }
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
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>批次（催收单）</div>
      <div class="ops">
        <span class="note" style="margin:0">批次列表 · 共 {{ total }}</span>
        <button v-if="auth.has('batch.import') || auth.has('proj.edit')" class="btn sm" @click="openImport">+ 导入批次</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:60px">ID</th>
          <th>批次号</th>
          <th style="width:110px">状态</th>
          <th v-if="showCommInRate" style="width:100px">收佣比例</th>
          <th v-if="showPayOutRate" style="width:120px">付佣比例</th>
          <th style="width:300px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td class="num">{{ row.id }}</td>
          <td><a class="link" @click="$router.push(`/batches/${row.id}`)">{{ row.code }}</a></td>
          <td><span class="tag" :class="statusTag(row.status)" :title="row.status">{{ caseStatusLabel(row.status) }}</span></td>
          <!-- 收佣比例：仅平台/物业视角整列渲染(服务商视角字段级无→整列不出 H-03) -->
          <td v-if="showCommInRate" class="num">{{ ratePct(row.commInRate) }}</td>
          <!-- 付佣比例：仅平台/服务商视角整列渲染(物业视角字段级无→整列不出，不显占位串 H-03) -->
          <td v-if="showPayOutRate" class="num">{{ ratePct(row.payOutRate) }}</td>
          <td>
            <a v-if="auth.has('case.dispatch')" class="btn txt" :class="{ 'is-disabled': acting===row.id }" @click="acting===row.id || openDispatch(row.id)">派单</a>
            <a v-if="auth.has('case.dispatch')" class="btn txt" @click="openDispatch(row.id, true)">重派</a>
            <a v-if="auth.has('case.dispatch')" class="btn txt" @click="setOpenRate(row)">开放费率</a>
            <a v-if="auth.has('case.void')" class="btn txt dgc" @click="voidBatch(row)">作废</a>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td :colspan="3 + (showCommInRate ? 1 : 0) + (showPayOutRate ? 1 : 0) + 1" style="text-align:center;color:var(--sec);padding:32px 0">暂无批次，点击「+ 导入批次」导入催收单。</td>
        </tr>
      </tbody>
    </table>

    <!-- 派单/重派 -->
    <DsDrawer v-model="dlg" :title="(form.redispatch?'重派':'派单')" :width="640">
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
          <el-table-column label="房号" width="90"><template #default="{row}"><el-input v-model="row.room" size="small" /></template></el-table-column>
          <el-table-column label="应收(元)" width="110"><template #default="{row}"><el-input-number v-model="row.dueYuan" size="small" :min="0" :controls="false" style="width:90px" /></template></el-table-column>
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
</template>

<style scoped>
/* 派单/导入弹窗内嵌 EL 表格保持原生主题，不受本页 ds-admin 原生 table 规则影响 */
.btn.txt.is-disabled { opacity: .5; cursor: not-allowed; }
</style>
