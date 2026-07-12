<script setup lang="ts">
// 组织额度详情（v1.19.0）：某一个组织的 余额卡 + 用量分析（月/日+明细下钻）+ 充值流水。
// 复用件：平台侧由 /quota/:orgId 详情页承载；物业/服务商在 /quota 直接看自己（range scope 天然裁剪，orgId 传 null）。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import DsDrawer from '../components/DsDrawer.vue'

const props = defineProps<{ orgId?: string | null }>()
const emit = defineEmits<{ (e: 'recharged'): void }>()

const auth = useAuth()
const canRecharge = computed(() => auth.has('billing.recharge'))
const isPlatform = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')

const TYPE_LABEL: Record<string, string> = { STT: '录音转写', SMS: '短信', EVIDENCE: '存证', LEGAL: '法律文书' }
const TYPE_ORDER = ['STT', 'SMS', 'EVIDENCE', 'LEGAL']
// 数量不是钱（只量不金额 BR-M10-01）：3 位小数去尾零 + 单位后缀。
const fmtQty = (v?: number | null, unit?: string | null) =>
  v == null ? '—' : (Number(v.toFixed(3)).toLocaleString('zh-CN') + (unit ?? ''))
const unitOf = (t: string) => (t === 'STT' ? '分钟' : t === 'SMS' ? '条' : t === 'LEGAL' ? '件' : '次')

// ── 余额卡（GET /billing/orgs：平台按 orgId 挑本组织行；非平台 range scope 已只返自己）──
const quotas = ref<any[]>([])
const orgName = computed(() => quotas.value[0]?.orgName ?? '')
const orgType = computed(() => quotas.value[0]?.orgType ?? '')
async function loadQuotas() {
  const { data, error } = await api.GET('/billing/orgs', { params: { query: { page: 1, size: 200 } } as any })
  if (error) { ElMessage.error('加载额度失败'); quotas.value = []; return }
  const all = ((data as any)?.items ?? [])
  const rows = props.orgId ? all.filter((q: any) => String(q.orgId) === String(props.orgId)) : all
  quotas.value = TYPE_ORDER.map((t) => rows.find((q: any) => q.type === t)).filter(Boolean)
}

// ── 用量分析 ──
const tab = ref<'usage' | 'log'>('usage')
const groupBy = ref<'month' | 'day'>('month')
const uType = ref('')
const summary = ref<any[]>([]); const uLoading = ref(false)
async function loadSummary() {
  uLoading.value = true
  const q: any = { groupBy: groupBy.value, page: 1, size: 200 }
  if (props.orgId) q.orgId = props.orgId
  if (uType.value) q.type = uType.value
  const { data, error } = await api.GET('/billing/usage/summary', { params: { query: q } as any })
  uLoading.value = false
  if (error) { ElMessage.error('加载用量失败'); summary.value = []; return }
  summary.value = (data as any)?.items ?? []
}
// 明细下钻（穿透列 业主/房号/项目/批次）
const dDlg = ref(false); const dRow = ref<any>(null); const dItems = ref<any[]>([]); const dLoading = ref(false)
async function openDetail(row: any) {
  dRow.value = row; dItems.value = []; dDlg.value = true; dLoading.value = true
  const q: any = { orgId: row.orgId, type: row.type, page: 1, size: 200 }
  if (row.bucket?.length === 7) q.month = row.bucket
  const { data, error } = await api.GET('/billing/usage', { params: { query: q } as any })
  dLoading.value = false
  if (error) { ElMessage.error('加载明细失败'); return }
  let items = (data as any)?.items ?? []
  if (row.bucket?.length === 10) items = items.filter((i: any) => (i.occurredAt ?? '').slice(0, 10) === row.bucket)
  dItems.value = items
}

// ── 充值流水 ──
const logs = ref<any[]>([]); const lLoading = ref(false); const lKind = ref<'' | 'credit' | 'debit'>('')
async function loadLogs() {
  lLoading.value = true
  const q: any = { page: 1, size: 200 }
  if (props.orgId) q.orgId = props.orgId
  const { data, error } = await api.GET('/billing/recharge-log', { params: { query: q } as any })
  lLoading.value = false
  if (error) { ElMessage.error('加载流水失败'); logs.value = []; return }
  logs.value = (data as any)?.items ?? []
}
// 充值/扣减看 delta 正负（旧代码判 type 含「充值」——type 恒为 STT/SMS…，永远匹配不上，是真 bug）
const filteredLogs = computed(() => {
  if (!lKind.value) return logs.value
  return logs.value.filter((l: any) => (lKind.value === 'credit' ? (l.delta ?? 0) > 0 : (l.delta ?? 0) < 0))
})

// ── 充值（平台·POST /billing/recharge）──
const rDlg = ref(false); const rSaving = ref(false)
const rForm = ref<any>({ type: 'STT', qty: 100, note: '' })
const rTypeOptions = computed(() => quotas.value.filter((q: any) => q.rechargeable).map((q: any) => q.type))
function openRecharge(type?: string) {
  rForm.value = { type: type ?? rTypeOptions.value[0] ?? 'STT', qty: 100, note: '' }
  rDlg.value = true
}
async function submitRecharge() {
  if (!props.orgId) return
  if (!rForm.value.qty || rForm.value.qty <= 0) { ElMessage.warning('充值数量须大于 0'); return }
  rSaving.value = true
  const { error } = await api.POST('/billing/recharge', {
    params: { header: { 'Idempotency-Key': crypto.randomUUID() } } as any,
    body: { orgId: String(props.orgId), type: rForm.value.type, qty: Number(rForm.value.qty), note: rForm.value.note || undefined } as any,
  })
  rSaving.value = false
  if (error) { ElMessage.error('充值失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已充值 ${rForm.value.qty}${unitOf(rForm.value.type)}（${orgName.value}）`)
  rDlg.value = false
  loadQuotas(); loadLogs(); emit('recharged')
}

function reload() { loadQuotas(); loadSummary(); loadLogs() }
watch(() => props.orgId, reload)
onMounted(reload)
defineExpose({ reload, orgName })
</script>

<template>
  <div>
    <!-- 非平台：线下充值提示 -->
    <div v-if="!isPlatform" class="alert info" style="margin-top:0;margin-bottom:12px">
      <span>额度由平台后台统一充值，请<b>线下联系平台运营</b>。存证/法律文书为<b>后付费</b>（按次计入对账），余额为负表示欠用记账。</span>
    </div>

    <!-- 余额卡：四类额度 -->
    <div class="kpis" style="grid-template-columns:repeat(4,1fr);margin-bottom:14px">
      <div v-for="q in quotas" :key="q.type" class="kpi">
        <div class="n" :style="{ color: (q.balance ?? 0) < 0 ? 'var(--danger)' : undefined }">
          {{ fmtQty(q.balance, q.unit) }}
          <sup v-if="(q.balance ?? 0) < 0" class="tag dan" style="margin-left:4px">欠用</sup>
        </div>
        <div class="l">
          {{ TYPE_LABEL[q.type] ?? q.type }} · 本月用 {{ fmtQty(q.usedThisMonth, q.unit) }}
          <button v-if="orgId && q.rechargeable && canRecharge" class="btn txt" style="padding:0;margin-left:6px" @click="openRecharge(q.type)">充值</button>
          <span v-else-if="orgId && !q.rechargeable" class="note">后付费</span>
        </div>
      </div>
    </div>

    <div class="segctrl" style="margin-bottom:12px">
      <span :class="{ on: tab === 'usage' }" @click="tab = 'usage'; loadSummary()">用量分析</span>
      <span :class="{ on: tab === 'log' }" @click="tab = 'log'; loadLogs()">充值流水</span>
    </div>

    <!-- 用量分析 -->
    <template v-if="tab === 'usage'">
      <div class="toolbar">
        <span class="segctrl">
          <span :class="{ on: groupBy === 'month' }" @click="groupBy = 'month'; loadSummary()">按月</span>
          <span :class="{ on: groupBy === 'day' }" @click="groupBy = 'day'; loadSummary()">按日</span>
        </span>
        <el-select v-model="uType" placeholder="全部类型" clearable style="width:150px;margin-left:8px" @change="loadSummary">
          <el-option v-for="(l, k) in TYPE_LABEL" :key="k" :label="l" :value="k" />
        </el-select>
      </div>
      <table v-loading="uLoading">
        <thead>
          <tr>
            <th style="width:140px">{{ groupBy === 'month' ? '月份' : '日期' }}</th>
            <th>额度类型</th><th style="width:150px">用量</th><th style="width:100px">笔数</th><th style="width:110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in summary" :key="r.bucket + r.type">
            <td><b>{{ r.bucket }}</b></td>
            <td>{{ TYPE_LABEL[r.type] ?? r.type }}</td>
            <td class="num">{{ fmtQty(r.qty, r.unit) }}</td>
            <td class="num">{{ r.count }}</td>
            <td><button class="btn txt" @click="openDetail(r)">查看明细</button></td>
          </tr>
          <tr v-if="!uLoading && !summary.length"><td colspan="5" class="note" style="text-align:center;padding:32px 0">暂无用量数据</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 充值流水 -->
    <template v-else>
      <div class="toolbar">
        <span class="segctrl">
          <span :class="{ on: lKind === '' }" @click="lKind = ''">全部</span>
          <span :class="{ on: lKind === 'credit' }" @click="lKind = 'credit'">充值</span>
          <span :class="{ on: lKind === 'debit' }" @click="lKind = 'debit'">扣减</span>
        </span>
      </div>
      <table v-loading="lLoading">
        <thead>
          <tr><th style="width:160px">时间</th><th style="width:110px">额度类型</th><th style="width:110px">变动</th><th style="width:120px">操作后余额</th><th>单据</th><th>备注</th><th style="width:100px">操作人</th></tr>
        </thead>
        <tbody>
          <tr v-for="l in filteredLogs" :key="l.id">
            <td>{{ (l.tm ?? '').slice(0, 19).replace('T', ' ') }}</td>
            <td>{{ TYPE_LABEL[l.type] ?? l.type }}</td>
            <td class="num"><span class="tag" :class="(l.delta ?? 0) > 0 ? 'suc' : 'war'">{{ (l.delta ?? 0) > 0 ? '+' : '' }}{{ l.delta }}</span></td>
            <td class="num">{{ l.balance }}</td>
            <td>{{ l.ref || '—' }}</td>
            <td>{{ l.note || '—' }}</td>
            <td>{{ l.operatedByName || '—' }}</td>
          </tr>
          <tr v-if="!lLoading && !filteredLogs.length"><td colspan="7" class="note" style="text-align:center;padding:32px 0">暂无流水</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 用量明细抽屉（穿透列）-->
    <DsDrawer v-model="dDlg" :title="`用量明细 · ${dRow?.bucket ?? ''} · ${TYPE_LABEL[dRow?.type] ?? ''}`" :width="820">
      <el-table v-loading="dLoading" :data="dItems" border size="small" max-height="560">
        <el-table-column label="时间" width="150"><template #default="{row}">{{ (row.occurredAt ?? '').slice(0, 19).replace('T', ' ') }}</template></el-table-column>
        <el-table-column label="用量" width="100"><template #default="{row}">{{ fmtQty(row.qty, row.unit) }}</template></el-table-column>
        <el-table-column prop="ownerName" label="业主" width="90" />
        <el-table-column prop="room" label="房号" width="90" />
        <el-table-column prop="projectName" label="项目" min-width="110" />
        <el-table-column prop="batchNo" label="批次" min-width="120" />
      </el-table>
      <div class="note" style="margin-top:6px">共 {{ dItems.length }} 笔 · 合计 {{ fmtQty(dItems.reduce((s: number, i: any) => s + (i.qty || 0), 0), dRow?.unit) }}</div>
      <template #footer><el-button @click="dDlg = false">关闭</el-button></template>
    </DsDrawer>

    <!-- 充值抽屉（组织已确定，只选类型/数量）-->
    <DsDrawer v-model="rDlg" :title="`充值 · ${orgName}`" :width="480">
      <div class="alert info" style="margin-top:0;margin-bottom:10px">
        <span>充值矩阵：<b>短信</b>仅物业可充；<b>录音转写</b>物业/服务商均可充；<b>存证/法律文书</b>为后付费，按次计入对账，不预充。</span>
      </div>
      <el-form label-width="100px">
        <el-form-item label="额度类型" required>
          <el-select v-model="rForm.type" style="width:180px">
            <el-option v-for="t in rTypeOptions" :key="t" :label="TYPE_LABEL[t]" :value="t" />
          </el-select>
          <span class="note" style="margin-left:8px">{{ unitOf(rForm.type) }}</span>
        </el-form-item>
        <el-form-item label="充值数量" required>
          <el-input-number v-model="rForm.qty" :min="1" :step="100" />
          <span class="note" style="margin-left:8px">{{ unitOf(rForm.type) }}</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rForm.note" type="textarea" :rows="2" placeholder="如：2026-07 季度预付" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rDlg = false">取消</el-button>
        <el-button type="primary" :loading="rSaving" @click="submitRecharge">确认充值</el-button>
      </template>
    </DsDrawer>
  </div>
</template>
