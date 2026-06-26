<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 计费(M9-B)：能力用量(GET /billing/usage·按次 STT/SMS/存证/法务·月→日→明细下钻) + 充值流水(recharge-log)
//          + 平台充值(recharge) + 短信发送明细(GET /sms-records·成功/失败/未达·失败汇总·导出 US-M9-04/BR-M9-08)。
const auth = useAuth()
const usage = ref<any[]>([])
const log = ref<any[]>([])
const orgs = ref<any[]>([])
const dlg = ref(false)
const form = ref<any>({ orgId: '', type: 'STT', qty: 100, note: '' })

// 能力用量「月→日→明细」下钻：type 维度 + month 过滤(契约 GET /billing/usage 入参 type/month)。
const usageType = ref<'STT' | 'SMS' | 'EVIDENCE' | 'LEGAL'>('STT')
const usageMonth = ref('') // YYYY-MM；空=全部

// 短信发送明细过滤(契约 GET /sms-records 入参 projectId/caseId/status/from/to/page/size)。
const sms = ref<any[]>([])
const smsFilter = ref<any>({ projectId: '', caseId: '', status: '', from: '', to: '' })
const smsRange = ref<string[]>([]) // [from, to] 由 el-date-picker 维护
const smsPage = ref(1)
const smsSize = ref(20)

async function load() {
  const u = await api.GET('/billing/usage', { params: { query: { type: usageType.value, month: usageMonth.value || undefined, page: 1, size: 100 } } as any })
  usage.value = (u.data as any)?.items ?? []
  const l = await api.GET('/billing/recharge-log', { params: { query: { page: 1, size: 20 } } as any })
  log.value = (l.data as any)?.items ?? []
  if (auth.has('billing.recharge')) {
    const o = await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })
    orgs.value = (o.data as any)?.items ?? []
  }
  await loadSms()
}

async function loadUsage() {
  const u = await api.GET('/billing/usage', { params: { query: { type: usageType.value, month: usageMonth.value || undefined, page: 1, size: 100 } } as any })
  usage.value = (u.data as any)?.items ?? []
}

// 月→日→明细：先按 occurredAt 客户端分组到「月」，展开见「日」聚合，再下钻到明细行(契约返回扁平 BillingUsage·无服务端日聚合)。
const usageTree = computed<any[]>(() => {
  const byMonth: Record<string, any> = {}
  for (const it of usage.value) {
    const occurred = String(it.occurredAt || '')
    const ym = occurred.slice(0, 7) || '未知'
    const ymd = occurred.slice(0, 10) || '未知'
    if (!byMonth[ym]) byMonth[ym] = { key: ym, label: ym, qty: 0, unit: it.unit, _days: {} }
    const m = byMonth[ym]
    m.qty += Number(it.qty) || 0
    if (!m.unit) m.unit = it.unit
    if (!m._days[ymd]) m._days[ymd] = { key: ym + '/' + ymd, label: ymd, qty: 0, unit: it.unit, children: [] }
    const d = m._days[ymd]
    d.qty += Number(it.qty) || 0
    if (!d.unit) d.unit = it.unit
    d.children.push({ key: ym + '/' + ymd + '/' + (it.id || d.children.length), label: it.caseId ? ('案件 ' + it.caseId) : '—', qty: Number(it.qty) || 0, unit: it.unit, caseId: it.caseId, occurredAt: it.occurredAt })
  }
  return Object.values(byMonth)
    .sort((a: any, b: any) => (a.label < b.label ? 1 : -1))
    .map((m: any) => ({ key: m.key, label: m.label, qty: m.qty, unit: m.unit, children: Object.values(m._days).sort((a: any, b: any) => (a.label < b.label ? 1 : -1)) }))
})

async function loadSms() {
  const f = smsFilter.value
  const sms_res = await api.GET('/sms-records', { params: { query: {
    projectId: f.projectId || undefined,
    caseId: f.caseId || undefined,
    status: f.status || undefined,
    from: f.from || undefined,
    to: f.to || undefined,
    page: smsPage.value,
    size: smsSize.value,
  } } as any })
  sms.value = (sms_res.data as any)?.items ?? []
}

function applySmsFilter() {
  const r = smsRange.value || []
  smsFilter.value.from = r[0] || ''
  smsFilter.value.to = r[1] || ''
  smsPage.value = 1
  loadSms()
}

function resetSmsFilter() {
  smsFilter.value = { projectId: '', caseId: '', status: '', from: '', to: '' }
  smsRange.value = []
  smsPage.value = 1
  loadSms()
}

function onSmsPage(p: number) {
  smsPage.value = p
  loadSms()
}

// 失败汇总(本页)：失败不退条数(BR-M9-08)，仅展示失败原因与占比。
const smsFailedCount = computed(() => sms.value.filter((r: any) => r.status === 'FAILED').length)
const smsFailedRate = computed(() => {
  const total = sms.value.length
  return total ? Math.round((smsFailedCount.value / total) * 100) : 0
})

const SMS_STATUS_MAP: Record<string, { text: string; type: string }> = {
  SENT: { text: '已发送', type: 'success' },
  DELIVERED: { text: '已送达', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}
function smsStatusText(s: string) { return (SMS_STATUS_MAP[s] || { text: s || '—' }).text }
function smsStatusType(s: string) { return (SMS_STATUS_MAP[s] || { type: 'info' }).type }
function smsRowClass(o: any) { return o.row && o.row.status === 'FAILED' ? 'sms-failed-row' : '' }

async function exportSms() {
  const f = smsFilter.value
  const { data, error } = await api.GET('/sms-records/export', { params: { query: {
    projectId: f.projectId || undefined,
    caseId: f.caseId || undefined,
    status: f.status || undefined,
    from: f.from || undefined,
    to: f.to || undefined,
  } } as any })
  if (error) { ElMessage.error('导出失败：' + ((error as any)?.message ?? '')); return }
  const url = (data as any)?.url
  if (url) { window.open(url, '_blank'); ElMessage.success('导出已生成') }
  else { ElMessage.info('导出文件生成中（文件通道占位·TBD）') }
}

async function recharge() {
  const { error } = await api.POST('/billing/recharge', { body: { orgId: String(form.value.orgId), type: form.value.type, qty: Number(form.value.qty), note: form.value.note } as any })
  if (error) { ElMessage.error('充值失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('充值成功'); dlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <el-card header="计费 · 能力用量与充值（按次：STT 转写/SMS 短信/存证/法务 — BR-M9-06a）">
    <el-button v-if="auth.has('billing.recharge')" type="primary" size="small" style="margin-bottom:10px" @click="dlg=true">平台充值</el-button>

    <el-divider content-position="left">能力用量（GET /billing/usage · 月→日→明细下钻 — 只量不金额 BR-M9-06b）</el-divider>
    <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <el-select v-model="usageType" size="small" style="width:160px" @change="loadUsage">
        <el-option label="STT 转写" value="STT" />
        <el-option label="SMS 短信" value="SMS" />
        <el-option label="存证 EVIDENCE" value="EVIDENCE" />
        <el-option label="法务 LEGAL" value="LEGAL" />
      </el-select>
      <el-date-picker v-model="usageMonth" type="month" value-format="YYYY-MM" placeholder="按月过滤(空=全部)" size="small" style="width:180px" @change="loadUsage" />
      <span style="color:#909399;font-size:12px">展开「月」见每日聚合，再展开「日」见明细行</span>
    </div>
    <el-table :data="usageTree" border size="small" row-key="key" :tree-props="{ children: 'children' }" default-expand-all>
      <el-table-column prop="label" label="周期 / 明细" min-width="220" />
      <el-table-column label="用量" width="160"><template #default="{row}">{{ row.qty }} {{ row.unit }}</template></el-table-column>
      <el-table-column prop="caseId" label="案件" width="120"><template #default="{row}">{{ row.caseId || '' }}</template></el-table-column>
      <el-table-column prop="occurredAt" label="时间" min-width="180"><template #default="{row}">{{ row.occurredAt || '' }}</template></el-table-column>
    </el-table>

    <el-divider content-position="left">短信发送明细（GET /sms-records · 成功/失败/未达 — US-M9-04/BR-M4-16）</el-divider>
    <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <el-input v-model="smsFilter.projectId" placeholder="项目ID" size="small" style="width:140px" clearable />
      <el-input v-model="smsFilter.caseId" placeholder="案件ID" size="small" style="width:140px" clearable />
      <el-select v-model="smsFilter.status" placeholder="状态" size="small" style="width:130px" clearable>
        <el-option label="已发送 SENT" value="SENT" />
        <el-option label="已送达 DELIVERED" value="DELIVERED" />
        <el-option label="失败 FAILED" value="FAILED" />
      </el-select>
      <el-date-picker v-model="smsRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="起" end-placeholder="止" size="small" style="width:340px" />
      <el-button type="primary" size="small" @click="applySmsFilter">查询</el-button>
      <el-button size="small" @click="resetSmsFilter">重置</el-button>
      <el-button size="small" @click="exportSms">导出</el-button>
    </div>
    <el-alert
      :title="'本页共 ' + sms.length + ' 条，失败 ' + smsFailedCount + ' 条（占 ' + smsFailedRate + '%）· 失败不退条数(BR-M9-08)，仅供查看失败原因'"
      :type="smsFailedCount > 0 ? 'warning' : 'info'"
      :closable="false"
      show-icon
      style="margin-bottom:10px"
    />
    <el-table :data="sms" border size="small" :row-class-name="smsRowClass">
      <el-table-column prop="sentAt" label="发送时间" min-width="170" />
      <el-table-column prop="template" label="模板" min-width="140"><template #default="{row}">{{ row.template || '—' }}</template></el-table-column>
      <el-table-column prop="caseId" label="案件" width="120"><template #default="{row}">{{ row.caseId || '—' }}</template></el-table-column>
      <el-table-column prop="projectId" label="项目" width="120"><template #default="{row}">{{ row.projectId || '—' }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="smsStatusType(row.status)" size="small">{{ smsStatusText(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="failureReason" label="失败原因" min-width="180"><template #default="{row}"><span style="color:#f56c6c">{{ row.status === 'FAILED' ? (row.failureReason || '未知') : '' }}</span></template></el-table-column>
    </el-table>
    <el-pagination
      layout="prev, pager, next"
      :current-page="smsPage"
      :page-size="smsSize"
      :total="sms.length < smsSize ? (smsPage - 1) * smsSize + sms.length : smsPage * smsSize + 1"
      style="margin-top:10px"
      @current-change="onSmsPage"
    />

    <el-divider content-position="left">充值流水（GET /billing/recharge-log）</el-divider>
    <el-table :data="log" border size="small">
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column label="变动"><template #default="{row}"><span :style="{color: row.delta>=0 ? '#67c23a':'#f56c6c'}">{{ row.delta>=0?'+':'' }}{{ row.delta }}</span></template></el-table-column>
      <el-table-column prop="balance" label="余额" />
      <el-table-column prop="ref" label="单据" /><el-table-column prop="tm" label="时间" />
    </el-table>

    <el-dialog v-model="dlg" title="平台充值（billing.recharge·仅平台）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="组织"><el-select v-model="form.orgId" placeholder="选择组织"><el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="STT 转写分钟" value="STT" /><el-option label="SMS 短信条数(仅物业)" value="SMS" /></el-select></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.qty" :min="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="recharge">充值</el-button></template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
:deep(.sms-failed-row) {
  background-color: #fef0f0;
}
</style>
