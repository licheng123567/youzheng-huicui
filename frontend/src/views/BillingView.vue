<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { billingTypeLabel } from '../constants/enums'

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

// 纯展示辅助（不改数据/接口）：能力用量 KPI（当前 type 维度的总量 + 笔数）
const usageTotal = computed(() => usage.value.reduce((s: number, it: any) => s + (Number(it.qty) || 0), 0))
const usageUnit = computed(() => (usage.value.find((it: any) => it.unit)?.unit) || '次')
const USAGE_TYPE_LABEL: Record<string, string> = { STT: 'STT 转写', SMS: 'SMS 短信', EVIDENCE: '存证', LEGAL: '法务' }
// 充值流水类型 → ds-admin .tag 配色（充值=绿、扣费=橙、其余=灰）
function logTagClass(t: string) {
  const s = String(t || '')
  if (s.includes('充值') || s.toUpperCase().includes('RECHARGE')) return 'suc'
  if (s.includes('扣') || s.toUpperCase().includes('DEDUCT') || s.toUpperCase().includes('CONSUME')) return 'war'
  return 'inf'
}

onMounted(load)
</script>

<template>
  <div>
    <!-- ① 能力用量（GET /billing/usage · 月→日→明细下钻 — 只量不金额 BR-M9-06b） -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>计费 · 能力用量</div>
        <div class="ops">
          <span class="note" style="margin:0">按次：STT 转写 / SMS 短信 / 存证 / 法务 — BR-M9-06a</span>
          <button v-if="auth.has('billing.recharge')" class="btn sm" @click="dlg=true">平台充值</button>
        </div>
      </div>

      <!-- 用量 KPI：当前 type 维度的总量 + 笔数（只量不金额 BR-M9-06b） -->
      <div class="kpis" style="grid-template-columns:repeat(2,1fr)">
        <div class="kpi">
          <div class="n">{{ usageTotal }} <span style="font-size:14px;font-weight:600;color:var(--sec)">{{ usageUnit }}</span></div>
          <div class="l">{{ USAGE_TYPE_LABEL[usageType] || usageType }} · 用量合计{{ usageMonth ? '（' + usageMonth + '）' : '（全部）' }}</div>
        </div>
        <div class="kpi">
          <div class="n">{{ usage.length }}</div>
          <div class="l">用量笔数（明细行）</div>
        </div>
      </div>

      <div class="sec-title">用量明细 · 月 → 日 → 明细下钻</div>
      <div class="search" style="margin-bottom:14px">
        <div class="fi">
          <span>能力</span>
          <el-select v-model="usageType" size="small" style="width:160px" @change="loadUsage">
            <el-option label="STT 转写" value="STT" />
            <el-option label="SMS 短信" value="SMS" />
            <el-option label="存证 EVIDENCE" value="EVIDENCE" />
            <el-option label="法务 LEGAL" value="LEGAL" />
          </el-select>
        </div>
        <div class="fi">
          <span>月份</span>
          <el-date-picker v-model="usageMonth" type="month" value-format="YYYY-MM" placeholder="按月过滤(空=全部)" size="small" style="width:180px" @change="loadUsage" />
        </div>
      </div>
      <div class="note" style="margin-top:0;margin-bottom:10px">展开「月」见每日聚合，再展开「日」见明细行；仅统计用量，不展示金额。</div>
      <el-table :data="usageTree" border size="small" row-key="key" :tree-props="{ children: 'children' }" default-expand-all>
        <el-table-column prop="label" label="周期 / 明细" min-width="220" />
        <el-table-column label="用量" width="160"><template #default="{row}">{{ row.qty }} {{ row.unit }}</template></el-table-column>
        <el-table-column prop="caseId" label="案件" width="120"><template #default="{row}">{{ row.caseId || '' }}</template></el-table-column>
        <el-table-column prop="occurredAt" label="时间" min-width="180"><template #default="{row}">{{ row.occurredAt || '' }}</template></el-table-column>
      </el-table>
    </div>

    <!-- ② 短信发送明细（GET /sms-records · 成功/失败/未达 — US-M9-04/BR-M4-16） -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>短信发送明细</div>
        <div class="ops"><span class="note" style="margin:0">GET /sms-records · 成功 / 失败 / 未达 — US-M9-04</span></div>
      </div>

      <div class="search" style="margin-bottom:14px">
        <div class="fi">
          <span>项目</span>
          <el-input v-model="smsFilter.projectId" placeholder="项目ID" size="small" style="width:140px" clearable />
        </div>
        <div class="fi">
          <span>案件</span>
          <el-input v-model="smsFilter.caseId" placeholder="案件ID" size="small" style="width:140px" clearable />
        </div>
        <div class="fi">
          <span>状态</span>
          <el-select v-model="smsFilter.status" placeholder="全部" size="small" style="width:130px" clearable>
            <el-option label="已发送 SENT" value="SENT" />
            <el-option label="已送达 DELIVERED" value="DELIVERED" />
            <el-option label="失败 FAILED" value="FAILED" />
          </el-select>
        </div>
        <div class="fi">
          <span>时间</span>
          <el-date-picker v-model="smsRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="起" end-placeholder="止" size="small" style="width:340px" />
        </div>
        <div class="fi">
          <button class="btn sm" @click="applySmsFilter">查询</button>
          <button class="btn df sm" @click="resetSmsFilter">重置</button>
          <button class="btn df sm" @click="exportSms">导出</button>
        </div>
      </div>

      <div class="alert" :class="smsFailedCount > 0 ? 'warn' : 'info'" style="margin-top:0">
        本页共 {{ sms.length }} 条，失败 {{ smsFailedCount }} 条（占 {{ smsFailedRate }}%）· 失败不退条数(BR-M9-08)，仅供查看失败原因。
      </div>

      <el-table :data="sms" border size="small" :row-class-name="smsRowClass" style="margin-top:12px">
        <el-table-column prop="sentAt" label="发送时间" min-width="170" />
        <el-table-column prop="template" label="模板" min-width="140"><template #default="{row}">{{ row.template || '—' }}</template></el-table-column>
        <el-table-column prop="caseId" label="案件" width="120"><template #default="{row}">{{ row.caseId || '—' }}</template></el-table-column>
        <el-table-column prop="projectId" label="项目" width="120"><template #default="{row}">{{ row.projectId || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="smsStatusType(row.status)" size="small">{{ smsStatusText(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="failureReason" label="失败原因" min-width="180"><template #default="{row}"><span style="color:var(--danger)">{{ row.status === 'FAILED' ? (row.failureReason || '未知') : '' }}</span></template></el-table-column>
      </el-table>
      <el-pagination
        layout="prev, pager, next"
        :current-page="smsPage"
        :page-size="smsSize"
        :total="sms.length < smsSize ? (smsPage - 1) * smsSize + sms.length : smsPage * smsSize + 1"
        style="margin-top:12px"
        @current-change="onSmsPage"
      />
    </div>

    <!-- ③ 充值流水（GET /billing/recharge-log） -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>充值流水</div>
        <div class="ops"><span class="note" style="margin:0">GET /billing/recharge-log · 余额 / 充值 / 扣费</span></div>
      </div>
      <table>
        <thead>
          <tr>
            <th style="width:120px">类型</th>
            <th style="width:140px">变动</th>
            <th style="width:140px">余额</th>
            <th>单据</th>
            <th style="width:180px">时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in log" :key="i">
            <td><span class="tag" :class="logTagClass(row.type)" :title="row.type">{{ billingTypeLabel(row.type) }}</span></td>
            <td class="num" :style="{ color: row.delta >= 0 ? 'var(--success)' : 'var(--danger)' }">{{ row.delta >= 0 ? '+' : '' }}{{ row.delta }}</td>
            <td class="num">{{ row.balance }}</td>
            <td>{{ row.ref || '—' }}</td>
            <td>{{ row.tm || '—' }}</td>
          </tr>
          <tr v-if="!log.length">
            <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无充值流水</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 平台充值弹窗（含校验表单 / el-input-number — 保留 EL 原样） -->
    <el-dialog v-model="dlg" title="平台充值（billing.recharge·仅平台）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="组织"><el-select v-model="form.orgId" placeholder="选择组织"><el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="STT 转写分钟" value="STT" /><el-option label="SMS 短信条数(仅物业)" value="SMS" /></el-select></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.qty" :min="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="recharge">充值</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.sms-failed-row) {
  background-color: #fef0f0;
}
</style>
