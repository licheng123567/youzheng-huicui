<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import { caseStatusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

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

// 批次2 导入：POST /batches/import（BatchImport: projectId/commInRate(分数)/rows[CaseImportRow]）
// 契约 ImportResult: { batch, total, succeeded, skipped, errors: ImportError[] }
// ImportError: { row, field, code, message }
const impDlg = ref(false)
const emptyRow = () => ({ acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, arrearPeriod: '', idCard: '', addr: '' })
const imp = ref<any>({ projectId: '', commInRate: 0.3, rows: [emptyRow()] })
const impResult = ref<any>(null)   // ImportResult 响应体，null=未提交
function openImport() { imp.value = { projectId: '', commInRate: 0.3, rows: [emptyRow()] }; impResult.value = null; impDlg.value = true }
async function submitImport() {
  if (!imp.value.projectId) { ElMessage.warning('请填项目 id'); return }
  const rows = imp.value.rows.map((r: any) => {
    const row: any = { acctNo: r.acctNo, ownerName: r.ownerName, phone: r.phone, room: r.room, dueCents: Math.round(r.dueYuan * 100), arrearPeriod: r.arrearPeriod }
    // 诉讼要素(选填)：仅在有值时带 litigation:{idCard,addr}(CaseImportRow.litigation·BR-M2-14)
    const idCard = (r.idCard || '').trim(); const addr = (r.addr || '').trim()
    if (idCard || addr) row.litigation = { ...(idCard ? { idCard } : {}), ...(addr ? { addr } : {}) }
    return row
  })
  // body 构造保持：projectId 字符串化、commInRate 数字、rows dueCents 单位=分
  const { data, error } = await api.POST('/batches/import', { body: { projectId: String(imp.value.projectId), commInRate: Number(imp.value.commInRate), rows } as any })
  if (error) { ElMessage.error('导入失败：' + ((error as any)?.message ?? '')); return }
  const result = data as any   // ImportResult
  impResult.value = result
  const hasErrors = result.errors && result.errors.length > 0
  // 仅全量成功(skipped===0 且无错误行)时自动关闭
  if (result.skipped === 0 && !hasErrors) {
    ElMessage.success(`导入完成：成功 ${result.succeeded}（共 ${result.total}）`)
    impDlg.value = false
    load()
  } else {
    ElMessage.warning(`导入完成：成功 ${result.succeeded} / 跳过 ${result.skipped}（共 ${result.total}），请查看下方错误明细`)
  }
}

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
        <button v-if="auth.has('batch.import')" class="btn sm" @click="openImport">+ 导入批次</button>
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

    <!-- 导入批次 -->
    <DsDrawer v-model="impDlg" title="导入批次" :width="820">
      <el-form :inline="true" label-width="90px">
        <el-form-item label="项目 id"><el-input v-model="imp.projectId" style="width:120px" placeholder="如 1" /></el-form-item>
        <el-form-item label="收佣比例"><el-input-number v-model="imp.commInRate" :min="0" :max="1" :step="0.01" /><span style="margin-left:6px;color:#909399">分数 0.3=30%</span></el-form-item>
      </el-form>
      <el-table :data="imp.rows" border size="small">
        <el-table-column label="户号"><template #default="{row}"><el-input v-model="row.acctNo" size="small" /></template></el-table-column>
        <el-table-column label="姓名"><template #default="{row}"><el-input v-model="row.ownerName" size="small" /></template></el-table-column>
        <el-table-column label="手机"><template #default="{row}"><el-input v-model="row.phone" size="small" /></template></el-table-column>
        <el-table-column label="房号"><template #default="{row}"><el-input v-model="row.room" size="small" /></template></el-table-column>
        <el-table-column label="应收(元)" width="110"><template #default="{row}"><el-input-number v-model="row.dueYuan" size="small" :min="0" :controls="false" style="width:90px" /></template></el-table-column>
        <el-table-column label="欠费期" width="100"><template #default="{row}"><el-input v-model="row.arrearPeriod" size="small" placeholder="2025-01" /></template></el-table-column>
        <el-table-column label="身份证号(选填)" width="150"><template #default="{row}"><el-input v-model="row.idCard" size="small" placeholder="诉讼要素·可后补" /></template></el-table-column>
        <el-table-column label="通讯地址(选填)" min-width="140"><template #default="{row}"><el-input v-model="row.addr" size="small" placeholder="诉讼要素·可后补" /></template></el-table-column>
        <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" @click="imp.rows.splice($index,1)" :disabled="imp.rows.length<=1">×</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" text type="primary" style="margin-top:6px" @click="imp.rows.push(emptyRow())">+ 添加行</el-button>

      <!-- 导入结果区：仅提交后且有跳过/错误时显示 -->
      <template v-if="impResult">
        <el-divider style="margin:16px 0 10px" />
        <div style="margin-bottom:8px">
          <el-tag type="success" style="margin-right:6px">成功 {{ impResult.succeeded }}</el-tag>
          <el-tag :type="impResult.skipped > 0 ? 'warning' : 'info'" style="margin-right:6px">跳过 {{ impResult.skipped }}</el-tag>
          <el-tag type="info">共 {{ impResult.total }}</el-tag>
        </div>
        <template v-if="impResult.errors && impResult.errors.length > 0">
          <div style="font-size:13px;color:#E6A23C;margin-bottom:6px">错误明细（以下行已跳过，其余行已继续导入）</div>
          <el-table :data="impResult.errors" border size="small" max-height="220">
            <el-table-column prop="row" label="行号" width="60" />
            <el-table-column prop="field" label="字段" width="120" />
            <el-table-column prop="code" label="错误码" width="140" />
            <el-table-column prop="message" label="消息" show-overflow-tooltip />
          </el-table>
        </template>
      </template>

      <template #footer>
        <el-button @click="impDlg=false; impResult=null">关闭</el-button>
        <el-button type="primary" @click="submitImport">导入</el-button>
      </template>
    </DsDrawer>
  </div>
</template>

<style scoped>
/* 派单/导入弹窗内嵌 EL 表格保持原生主题，不受本页 ds-admin 原生 table 规则影响 */
.btn.txt.is-disabled { opacity: .5; cursor: not-allowed; }
</style>
