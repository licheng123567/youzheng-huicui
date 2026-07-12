<script setup lang="ts">
// 「额度管理」（v1.19.0）：计费明细 + 充值中心合并页，以**组织**为聚合。
//   平台(SA/SE)：三 Tab —— 组织额度总览（余额/本月/上月用量 + 充值）、用量分析（组织×类型×月/日）、充值流水。
//     SA 有 billing.recharge 可充值；SE 同屏只读（按钮禁用）。
//   物业/服务商(PL/VL)：同页只见自己（range scope 天然裁剪）——余额卡 + 用量分析 + 流水；无充值按钮（线下联系平台）。
// 余额权威源=org_balance(V932)；EVIDENCE/LEGAL 后付费，余额可为负（欠用记账）。
// 充值矩阵（SMS 仅物业 / STT 物业+服务商 / 存证法务不预充）由后端 rechargeable 字段下发，前端不复刻规则。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import DsDrawer from '../components/DsDrawer.vue'

const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const isPlatform = computed(() => role.value === 'SA' || role.value === 'SE')
const canRecharge = computed(() => auth.has('billing.recharge'))   // 仅 SA；SE 无此权限点

const TYPE_LABEL: Record<string, string> = { STT: '录音转写', SMS: '短信', EVIDENCE: '存证', LEGAL: '法律文书' }
// 数量不是钱（BR-M10-01 只量不金额）——不用 money.ts 的 yuan()；3 位小数去尾零 + 单位后缀。
const fmtQty = (v?: number | null, unit?: string | null) =>
  v == null ? '—' : (Number(v.toFixed(3)).toLocaleString('zh-CN') + (unit ?? ''))

const tab = ref<'quota' | 'usage' | 'log'>('quota')

// ── Tab1 组织额度总览（GET /billing/orgs）──
const quotas = ref<any[]>([]); const qLoading = ref(false)
async function loadQuotas() {
  qLoading.value = true
  const { data, error } = await api.GET('/billing/orgs', { params: { query: { page: 1, size: 200 } } as any })
  qLoading.value = false
  if (error) { ElMessage.error('加载组织额度失败'); quotas.value = []; return }
  quotas.value = (data as any)?.items ?? []
}
// 非平台视角：本组织四类型即余额卡
const myQuotas = computed(() => quotas.value)

// ── Tab2 用量分析（GET /billing/usage/summary）──
const groupBy = ref<'month' | 'day'>('month')
const uOrgId = ref(''); const uType = ref(''); const uFrom = ref(''); const uTo = ref('')
const summary = ref<any[]>([]); const uLoading = ref(false)
async function loadSummary() {
  uLoading.value = true
  const q: any = { groupBy: groupBy.value, page: 1, size: 200 }
  if (uOrgId.value) q.orgId = uOrgId.value
  if (uType.value) q.type = uType.value
  if (uFrom.value) q.from = uFrom.value
  if (uTo.value) q.to = uTo.value
  const { data, error } = await api.GET('/billing/usage/summary', { params: { query: q } as any })
  uLoading.value = false
  if (error) { ElMessage.error('加载用量聚合失败'); summary.value = []; return }
  summary.value = (data as any)?.items ?? []
}
// 明细下钻（GET /billing/usage：穿透列 业主/房号/项目/批次）
const dDlg = ref(false); const dRow = ref<any>(null); const dItems = ref<any[]>([]); const dLoading = ref(false)
async function openDetail(row: any) {
  dRow.value = row; dItems.value = []; dDlg.value = true; dLoading.value = true
  const q: any = { orgId: row.orgId, type: row.type, page: 1, size: 200 }
  if (row.bucket?.length === 7) q.month = row.bucket          // 月桶 → month 过滤
  const { data, error } = await api.GET('/billing/usage', { params: { query: q } as any })
  dLoading.value = false
  if (error) { ElMessage.error('加载用量明细失败'); return }
  let items = (data as any)?.items ?? []
  if (row.bucket?.length === 10) {                             // 日桶 → 前端按日期再筛（后端按月过滤）
    items = items.filter((i: any) => (i.occurredAt ?? '').slice(0, 10) === row.bucket)
  }
  dItems.value = items
}

// ── Tab3 充值流水（GET /billing/recharge-log）──
const logs = ref<any[]>([]); const lLoading = ref(false)
const lOrgId = ref(''); const lKind = ref<'' | 'credit' | 'debit'>('')
async function loadLogs() {
  lLoading.value = true
  const q: any = { page: 1, size: 200 }
  if (lOrgId.value) q.orgId = lOrgId.value
  const { data, error } = await api.GET('/billing/recharge-log', { params: { query: q } as any })
  lLoading.value = false
  if (error) { ElMessage.error('加载充值流水失败'); logs.value = []; return }
  logs.value = (data as any)?.items ?? []
}
// 修既有真 bug：原按 type 字符串含「充值」判断——type 恒为 STT/SMS/EVIDENCE/LEGAL，永远匹配不上。
// 充值/扣减看的是 delta 正负。
const filteredLogs = computed(() => {
  if (!lKind.value) return logs.value
  return logs.value.filter((l: any) => (lKind.value === 'credit' ? (l.delta ?? 0) > 0 : (l.delta ?? 0) < 0))
})

// ── 充值抽屉（POST /billing/recharge·带 Idempotency-Key）──
const rDlg = ref(false); const rSaving = ref(false)
const rForm = ref<any>({ orgId: '', orgName: '', orgType: '', type: 'STT', qty: 100, note: '' })
const orgOptions = ref<any[]>([])
async function loadOrgOptions() {
  if (orgOptions.value.length) return
  const [prop, prov] = await Promise.all([
    api.GET('/orgs', { params: { query: { type: 'PROPERTY', status: 'ACTIVE', page: 1, size: 200 } } as any }),
    api.GET('/orgs', { params: { query: { type: 'PROVIDER', status: 'ACTIVE', page: 1, size: 200 } } as any }),
  ])
  orgOptions.value = [...(((prop.data as any)?.items) ?? []), ...(((prov.data as any)?.items) ?? [])]
}
// 类型选项随 org 类型动态（与后端矩阵同源：物业 STT/SMS；服务商仅 STT）
const rTypeOptions = computed(() =>
  rForm.value.orgType === 'PROPERTY' ? ['STT', 'SMS'] : rForm.value.orgType === 'PROVIDER' ? ['STT'] : [])
function onPickOrg(id: string) {
  const o = orgOptions.value.find((x: any) => String(x.id) === String(id))
  rForm.value.orgType = o?.type ?? ''
  if (!rTypeOptions.value.includes(rForm.value.type)) rForm.value.type = rTypeOptions.value[0] ?? 'STT'
}
/** 行内充值（org/type 已知，无需选择器）。 */
function openRechargeRow(row: any) {
  rForm.value = { orgId: row.orgId, orgName: row.orgName, orgType: row.orgType, type: row.type, qty: 100, note: '' }
  rDlg.value = true
  loadOrgOptions()
}
/** 通用充值（页首按钮）：先选组织。 */
function openRecharge() {
  rForm.value = { orgId: '', orgName: '', orgType: '', type: 'STT', qty: 100, note: '' }
  rDlg.value = true
  loadOrgOptions()
}
async function submitRecharge() {
  if (!rForm.value.orgId) { ElMessage.warning('请选择组织'); return }
  if (!rForm.value.qty || rForm.value.qty <= 0) { ElMessage.warning('充值数量须大于 0'); return }
  rSaving.value = true
  const { error } = await api.POST('/billing/recharge', {
    params: { header: { 'Idempotency-Key': crypto.randomUUID() } } as any,
    body: { orgId: String(rForm.value.orgId), type: rForm.value.type, qty: Number(rForm.value.qty), note: rForm.value.note || undefined } as any,
  })
  rSaving.value = false
  if (error) { ElMessage.error('充值失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已充值 ${rForm.value.qty}${unitOf(rForm.value.type)}（${rForm.value.orgName || rForm.value.orgId}）`)
  rDlg.value = false
  loadQuotas(); if (tab.value === 'log') loadLogs()
}
const unitOf = (t: string) => (t === 'STT' ? '分钟' : t === 'SMS' ? '条' : t === 'LEGAL' ? '件' : '次')

onMounted(() => { loadQuotas(); loadSummary(); loadLogs() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>额度管理</div>
      <div class="ops">
        <span class="note" style="margin:0">能力额度：录音转写/短信（预付）· 存证/法律文书（后付·按次计入对账）</span>
        <button v-if="isPlatform && canRecharge" class="btn sm" @click="openRecharge">+ 充值</button>
      </div>
    </div>

    <!-- 非平台：本组织余额卡 + 线下充值提示 -->
    <template v-if="!isPlatform">
      <div class="alert info" style="margin-top:0;margin-bottom:12px">
        <span>额度由平台后台统一充值，请<b>线下联系平台运营</b>。存证/法律文书为<b>后付费</b>（按次计入对账），余额为负表示欠用记账。</span>
      </div>
      <div class="kpis" style="grid-template-columns:repeat(4,1fr);margin-bottom:14px">
        <div v-for="q in myQuotas" :key="q.type" class="kpi">
          <div class="n" :style="{ color: (q.balance ?? 0) < 0 ? 'var(--danger)' : undefined }">
            {{ fmtQty(q.balance, q.unit) }}
            <sup v-if="(q.balance ?? 0) < 0" class="tag dan" style="margin-left:4px">欠用</sup>
          </div>
          <div class="l">{{ TYPE_LABEL[q.type] ?? q.type }} · 本月用 {{ fmtQty(q.usedThisMonth, q.unit) }}</div>
        </div>
      </div>
    </template>

    <div class="segctrl" style="margin-bottom:12px">
      <span v-if="isPlatform" :class="{ on: tab === 'quota' }" @click="tab = 'quota'; loadQuotas()">组织额度总览</span>
      <span :class="{ on: tab === 'usage' }" @click="tab = 'usage'; loadSummary()">用量分析</span>
      <span :class="{ on: tab === 'log' }" @click="tab = 'log'; loadLogs()">充值流水</span>
    </div>

    <!-- Tab1 组织额度总览（平台）-->
    <template v-if="isPlatform && tab === 'quota'">
      <table v-loading="qLoading">
        <thead>
          <tr>
            <th>组织</th><th style="width:90px">类型</th><th>额度类型</th>
            <th style="width:120px">余额</th><th style="width:110px">本月用量</th><th style="width:110px">上月用量</th>
            <th style="width:110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in quotas" :key="q.orgId + q.type">
            <td><b>{{ q.orgName }}</b></td>
            <td><span class="tag inf">{{ q.orgType === 'PROPERTY' ? '物业' : '服务商' }}</span></td>
            <td>{{ TYPE_LABEL[q.type] ?? q.type }}</td>
            <td class="num">
              <span :class="(q.balance ?? 0) < 0 ? 'tag dan' : ''">{{ fmtQty(q.balance, q.unit) }}</span>
              <span v-if="(q.balance ?? 0) < 0" class="note" style="margin-left:4px">欠用</span>
            </td>
            <td class="num">{{ fmtQty(q.usedThisMonth, q.unit) }}</td>
            <td class="num">{{ fmtQty(q.usedLastMonth, q.unit) }}</td>
            <td>
              <el-tooltip v-if="q.rechargeable && !canRecharge" content="平台运营无充值权限（仅超管可充值）" placement="top">
                <span><button class="btn txt" disabled>充值</button></span>
              </el-tooltip>
              <button v-else-if="q.rechargeable" class="btn txt" @click="openRechargeRow(q)">充值</button>
              <span v-else class="note">后付费·不预充</span>
            </td>
          </tr>
          <tr v-if="!qLoading && !quotas.length"><td colspan="7" class="note" style="text-align:center;padding:32px 0">暂无组织额度数据</td></tr>
        </tbody>
      </table>
    </template>

    <!-- Tab2 用量分析（组织 × 类型 × 月/日）-->
    <template v-if="tab === 'usage'">
      <div class="toolbar">
        <span class="segctrl">
          <span :class="{ on: groupBy === 'month' }" @click="groupBy = 'month'; loadSummary()">按月</span>
          <span :class="{ on: groupBy === 'day' }" @click="groupBy = 'day'; loadSummary()">按日</span>
        </span>
        <el-select v-if="isPlatform" v-model="uOrgId" placeholder="全部组织" clearable filterable style="width:180px;margin-left:8px" @change="loadSummary">
          <el-option v-for="o in quotas.filter((q:any,i:number,a:any[]) => a.findIndex((x:any)=>x.orgId===q.orgId)===i)"
            :key="o.orgId" :label="o.orgName" :value="o.orgId" />
        </el-select>
        <el-select v-model="uType" placeholder="全部类型" clearable style="width:140px;margin-left:8px" @change="loadSummary">
          <el-option v-for="(l, k) in TYPE_LABEL" :key="k" :label="l" :value="k" />
        </el-select>
        <el-date-picker v-model="uFrom" type="date" value-format="YYYY-MM-DD" placeholder="起始日" clearable style="width:140px;margin-left:8px" @change="loadSummary" />
        <el-date-picker v-model="uTo" type="date" value-format="YYYY-MM-DD" placeholder="截止日" clearable style="width:140px;margin-left:8px" @change="loadSummary" />
      </div>
      <table v-loading="uLoading">
        <thead>
          <tr>
            <th style="width:130px">{{ groupBy === 'month' ? '月份' : '日期' }}</th>
            <th>组织</th><th>额度类型</th><th style="width:130px">用量</th><th style="width:90px">笔数</th><th style="width:110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in summary" :key="r.bucket + r.orgId + r.type">
            <td><b>{{ r.bucket }}</b></td>
            <td>{{ r.orgName }}</td>
            <td>{{ TYPE_LABEL[r.type] ?? r.type }}</td>
            <td class="num">{{ fmtQty(r.qty, r.unit) }}</td>
            <td class="num">{{ r.count }}</td>
            <td><button class="btn txt" @click="openDetail(r)">查看明细</button></td>
          </tr>
          <tr v-if="!uLoading && !summary.length"><td colspan="6" class="note" style="text-align:center;padding:32px 0">暂无用量数据</td></tr>
        </tbody>
      </table>
    </template>

    <!-- Tab3 充值流水 -->
    <template v-if="tab === 'log'">
      <div class="toolbar">
        <span class="segctrl">
          <span :class="{ on: lKind === '' }" @click="lKind = ''">全部</span>
          <span :class="{ on: lKind === 'credit' }" @click="lKind = 'credit'">充值</span>
          <span :class="{ on: lKind === 'debit' }" @click="lKind = 'debit'">扣减</span>
        </span>
        <el-select v-if="isPlatform" v-model="lOrgId" placeholder="全部组织" clearable filterable style="width:180px;margin-left:8px" @change="loadLogs">
          <el-option v-for="o in quotas.filter((q:any,i:number,a:any[]) => a.findIndex((x:any)=>x.orgId===q.orgId)===i)"
            :key="o.orgId" :label="o.orgName" :value="o.orgId" />
        </el-select>
      </div>
      <table v-loading="lLoading">
        <thead>
          <tr>
            <th style="width:160px">时间</th>
            <th v-if="isPlatform">组织</th>
            <th style="width:100px">额度类型</th><th style="width:110px">变动</th><th style="width:110px">操作后余额</th>
            <th>单据</th><th>备注</th><th style="width:100px">操作人</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in filteredLogs" :key="l.id">
            <td>{{ (l.tm ?? '').slice(0, 19).replace('T', ' ') }}</td>
            <td v-if="isPlatform">{{ l.orgName }}</td>
            <td>{{ TYPE_LABEL[l.type] ?? l.type }}</td>
            <!-- 充值/扣减看 delta 正负（原代码判 type 含「充值」，type 恒为 STT/SMS…，永远匹配不上 = 真 bug） -->
            <td class="num"><span class="tag" :class="(l.delta ?? 0) > 0 ? 'suc' : 'war'">{{ (l.delta ?? 0) > 0 ? '+' : '' }}{{ l.delta }}</span></td>
            <td class="num">{{ l.balance }}</td>
            <td>{{ l.ref || '—' }}</td>
            <td>{{ l.note || '—' }}</td>
            <td>{{ l.operatedByName || '—' }}</td>
          </tr>
          <tr v-if="!lLoading && !filteredLogs.length"><td :colspan="isPlatform ? 8 : 7" class="note" style="text-align:center;padding:32px 0">暂无流水</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 用量明细抽屉（穿透列：业主/房号/项目/批次）-->
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

    <!-- 充值抽屉（平台·POST /billing/recharge）-->
    <DsDrawer v-model="rDlg" title="后台充值能力额度" :width="520">
      <div class="alert info" style="margin-top:0;margin-bottom:10px">
        <span>充值矩阵：<b>短信</b>仅物业可充；<b>录音转写</b>物业/服务商均可充；<b>存证/法律文书</b>为后付费，按次计入对账，不预充。</span>
      </div>
      <el-form label-width="100px">
        <el-form-item label="组织" required>
          <el-select v-model="rForm.orgId" filterable placeholder="搜索/选择组织" style="width:280px" @change="onPickOrg">
            <el-option v-for="o in orgOptions" :key="o.id" :value="String(o.id)"
              :label="o.name + (o.type === 'PROPERTY' ? '（物业）' : '（服务商）')" />
          </el-select>
        </el-form-item>
        <el-form-item label="额度类型" required>
          <el-select v-model="rForm.type" style="width:180px" :disabled="!rForm.orgType">
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
        <el-button type="primary" :loading="rSaving" :disabled="!rForm.orgId" @click="submitRecharge">确认充值</el-button>
      </template>
    </DsDrawer>
  </div>
</template>
