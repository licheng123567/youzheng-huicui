<script setup lang="ts">
// 组织短信通道详情（v1.21.0）：某个物业的 签名/冷却/开关配置 + 短信模板 + 发送记录。
// 复用件：平台侧由 /sms/:orgId 详情页承载；物业在 /sms 直接看自己（orgId=null，range scope 天然裁剪）。
// 【平台统一配置·物业只读】签名与模板由平台配（settings.manage → 仅 SA）；模板由平台代向运营商报备。
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import DsDrawer from './DsDrawer.vue'

const props = defineProps<{ orgId?: string | null }>()

const auth = useAuth()
const canManage = computed(() => auth.has('settings.manage'))   // 仅 SA；SE/PL 只读

const KIND_LABEL: Record<string, string> = { PAY_LINK: '缴费链接', NOTIFY: '通知', VIDEO_NOTIFY: '视频通知' }
const ST_LABEL: Record<string, string> = { DRAFT: '待报备', ACTIVE: '已生效', REJECTED: '驳回', ARCHIVED: '已归档' }
const ST_TAG: Record<string, string> = { DRAFT: 'war', ACTIVE: 'suc', REJECTED: 'dan', ARCHIVED: 'inf' }
const VARS = ['payUrl', 'ownerName', 'amount', 'projectName', 'room', 'dueDate']

// ── 配置 + 模板（GET /orgs/{id}/sms-config）──
const cfg = ref<any>(null)
const myOrgId = computed(() => props.orgId ?? cfg.value?.orgId ?? '')
async function loadConfig() {
  // 物业侧无 orgId 入参：先用 /sms/orgs 拿到自己的 orgId（range scope 只返自己一行）
  let id = props.orgId
  if (!id) {
    const { data } = await api.GET('/sms/orgs', { params: { query: { page: 1, size: 5 } } as any })
    id = (data as any)?.items?.[0]?.orgId
    if (!id) { cfg.value = null; return }
  }
  const { data, error } = await api.GET('/orgs/{id}/sms-config', { params: { path: { id: String(id) } } })
  if (error) { ElMessage.error('加载短信配置失败'); cfg.value = null; return }
  cfg.value = data
}

// ── 编辑配置（平台·SA）──
const cDlg = ref(false); const cSaving = ref(false)
const cForm = ref<any>({ signName: '', cooldownMinutes: 360, payLinkTtlDays: 7, warnThreshold: null, enabled: true })
function openConfig() {
  cForm.value = {
    signName: cfg.value?.signName ?? '',
    cooldownMinutes: cfg.value?.cooldownMinutes ?? 360,
    payLinkTtlDays: cfg.value?.payLinkTtlDays ?? 7,
    warnThreshold: cfg.value?.warnThreshold ?? null,
    enabled: cfg.value?.enabled ?? true,
  }
  cDlg.value = true
}
async function submitConfig() {
  if (!cForm.value.signName?.trim()) { ElMessage.warning('短信签名必填'); return }
  cSaving.value = true
  const { error } = await api.PUT('/orgs/{id}/sms-config', {
    params: { path: { id: String(myOrgId.value) }, header: { 'Idempotency-Key': crypto.randomUUID() } } as any,
    body: { ...cForm.value, signName: cForm.value.signName.trim() } as any,
  })
  cSaving.value = false
  if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已保存短信配置（立即生效）')
  cDlg.value = false; loadConfig()
}

// ── 模板：新建/编辑草稿 + 回填报备结果 ──
const tDlg = ref(false); const tSaving = ref(false); const tEditId = ref('')
const tForm = ref<any>({ kind: 'PAY_LINK', name: '', content: '', varOrder: ['payUrl'] })
function openTemplate(row?: any) {
  tEditId.value = row?.id ?? ''
  tForm.value = row
    ? { kind: row.kind, name: row.name, content: row.content, varOrder: [...(row.varOrder ?? [])] }
    : { kind: 'PAY_LINK', name: '', content: '您有一笔物业费待缴，请点击缴费：{0}', varOrder: ['payUrl'] }
  tDlg.value = true
}
async function submitTemplate() {
  if (!tForm.value.name?.trim() || !tForm.value.content?.trim()) { ElMessage.warning('模板名称与正文必填'); return }
  tSaving.value = true
  const body = { kind: tForm.value.kind, name: tForm.value.name.trim(), content: tForm.value.content.trim(), varOrder: tForm.value.varOrder }
  const r = tEditId.value
    ? await api.PUT('/sms-templates/{tplId}', { params: { path: { tplId: tEditId.value } }, body: body as any })
    : await api.POST('/orgs/{id}/sms-templates', {
        params: { path: { id: String(myOrgId.value) }, header: { 'Idempotency-Key': crypto.randomUUID() } } as any,
        body: body as any,
      })
  tSaving.value = false
  if (r.error) { ElMessage.error('保存失败：' + ((r.error as any)?.message ?? '')); return }
  ElMessage.success(tEditId.value ? '草稿已更新' : '草稿已创建（待平台向运营商报备）')
  tDlg.value = false; loadConfig()
}
async function delTemplate(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除模板「${row.name}」？`, '删除模板', { type: 'warning' })
  } catch { return }
  const { error } = await api.DELETE('/sms-templates/{tplId}', { params: { path: { tplId: row.id } } })
  if (error) { ElMessage.error('删除失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已删除'); loadConfig()
}

// 报备结果回填（线下报备后）
const rDlg = ref(false); const rSaving = ref(false); const rRow = ref<any>(null)
const rForm = ref<any>({ result: 'ACTIVE', gatewayTemplateId: '', reason: '' })
function openRegister(row: any) {
  rRow.value = row
  rForm.value = { result: 'ACTIVE', gatewayTemplateId: row.gatewayTemplateId ?? '', reason: '' }
  rDlg.value = true
}
async function submitRegister() {
  if (rForm.value.result === 'ACTIVE' && !rForm.value.gatewayTemplateId?.trim()) {
    ElMessage.warning('生效必须回填运营商模板ID'); return
  }
  rSaving.value = true
  const { error } = await api.POST('/sms-templates/{tplId}/register', {
    params: { path: { tplId: rRow.value.id }, header: { 'Idempotency-Key': crypto.randomUUID() } } as any,
    body: {
      result: rForm.value.result,
      gatewayTemplateId: rForm.value.gatewayTemplateId?.trim() || undefined,
      reason: rForm.value.reason?.trim() || undefined,
    } as any,
  })
  rSaving.value = false
  if (error) { ElMessage.error('回填失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(rForm.value.result === 'ACTIVE' ? '模板已生效（旧版自动归档）' : '已记录驳回')
  rDlg.value = false; loadConfig()
}

// ── 发送记录（后端分页 + 全量统计）──
const filter = ref<any>({ from: '', to: '', status: '' })
const items = ref<any[]>([]); const total = ref(0); const page = ref(1); const size = 20
const stats = ref<any>({ total: 0, sent: 0, failed: 0, delivered: 0, failReasons: [] })
const loading = ref(false)
async function loadRecords() {
  loading.value = true
  const q: any = { page: page.value, size }
  if (props.orgId) q.orgId = props.orgId
  if (filter.value.from) q.from = filter.value.from
  if (filter.value.to) q.to = filter.value.to
  if (filter.value.status) q.status = filter.value.status
  const [rec, st] = await Promise.all([
    api.GET('/sms-records', { params: { query: q } as any }),
    api.GET('/sms-records/stats', { params: { query: { ...q, page: undefined, size: undefined } } as any }),
  ])
  loading.value = false
  if (rec.error) { ElMessage.error('加载发送明细失败'); items.value = []; return }
  items.value = (rec.data as any)?.items ?? []
  total.value = (rec.data as any)?.meta?.total ?? 0
  // KPI 与失败原因来自**全量统计端点**（此前是前端从首页 100 条 computed，超 100 条即失真）
  if (!st.error) stats.value = st.data
}
function resetFilter() { filter.value = { from: '', to: '', status: '' }; page.value = 1; loadRecords() }
function onFilter() { page.value = 1; loadRecords() }
const statusName = (s: string) => (s === 'SENT' ? '成功' : s === 'FAILED' ? '失败' : s === 'DELIVERED' ? '已达' : s)
const statusTag = (s: string) => (s === 'SENT' ? 'suc' : s === 'FAILED' ? 'dan' : 'inf')

// 导出：后端 /sms-records/export 恒返 url:null（文件通道 TBD）→ 前端按当前筛选生成 CSV 直接下载
function exportCsv() {
  const head = ['时间', '组织', '案件', '模板', '状态', '失败原因']
  const rows = items.value.map((s: any) => [
    s.sentAt ?? '', s.orgName ?? '', s.caseId ?? '', s.template ?? '',
    statusName(s.status), s.status === 'FAILED' ? (s.failureReason ?? '未知') : '',
  ])
  const csv = [head, ...rows].map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `短信明细_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

function reload() { loadConfig(); loadRecords() }
watch(() => props.orgId, reload)
onMounted(reload)
</script>

<template>
  <div>
    <!-- 短信配置（平台统一配置·物业只读）-->
    <div class="sec-title" style="margin-top:0">
      短信配置
      <span style="font-size:12px;color:var(--sec);font-weight:400">签名与模板由平台统一配置并代向运营商报备</span>
      <button v-if="canManage && cfg" class="btn sm" style="margin-left:auto" @click="openConfig">编辑配置</button>
    </div>
    <div v-if="!canManage" class="alert info" style="margin-top:0;margin-bottom:10px">
      <span>短信签名与模板由<b>平台统一配置</b>并代向运营商报备，本组织为只读视图；如需调整请联系平台运营。</span>
    </div>
    <div v-if="cfg" class="kpis" style="grid-template-columns:repeat(5,1fr);margin-bottom:14px">
      <div class="kpi">
        <div class="n" style="font-size:15px">{{ cfg.signName }}</div>
        <div class="l">短信签名 <span v-if="!cfg.configured" class="tag inf">平台默认</span></div>
      </div>
      <div class="kpi"><div class="n">{{ cfg.cooldownMinutes }}</div><div class="l">同案冷却(分钟)</div></div>
      <div class="kpi"><div class="n">{{ cfg.payLinkTtlDays }}</div><div class="l">链接有效期(天)</div></div>
      <div class="kpi"><div class="n">{{ cfg.warnThreshold ?? '—' }}</div><div class="l">余额预警(条)</div></div>
      <div class="kpi">
        <div class="n"><span class="tag" :class="cfg.enabled ? 'suc' : 'dan'">{{ cfg.enabled ? '正常' : '已停用' }}</span></div>
        <div class="l">通道状态</div>
      </div>
    </div>

    <!-- 短信模板（平台代报备）-->
    <div class="sec-title">
      短信模板
      <span style="font-size:12px;color:var(--sec);font-weight:400">运营商要求模板先报备拿到模板ID才能发送；DRAFT 不会被用于发送</span>
      <button v-if="canManage && cfg" class="btn sm" style="margin-left:auto" @click="openTemplate()">+ 新建草稿</button>
    </div>
    <table style="margin-bottom:14px">
      <thead>
        <tr><th style="width:110px">用途</th><th>名称</th><th>正文</th><th style="width:150px">变量顺序</th><th style="width:100px">状态</th><th style="width:130px">运营商模板ID</th><th v-if="canManage" style="width:170px">操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in (cfg?.templates ?? [])" :key="t.id">
          <td>{{ KIND_LABEL[t.kind] ?? t.kind }}</td>
          <td><b>{{ t.name }}</b></td>
          <td class="note" style="margin:0">{{ t.content }}</td>
          <td class="note" style="margin:0">{{ (t.varOrder ?? []).join(' → ') || '无变量' }}</td>
          <td>
            <span class="tag" :class="ST_TAG[t.status]">{{ ST_LABEL[t.status] ?? t.status }}</span>
            <div v-if="t.status === 'REJECTED' && t.rejectReason" class="note" style="margin:2px 0 0">{{ t.rejectReason }}</div>
          </td>
          <td>{{ t.gatewayTemplateId || '—' }}</td>
          <td v-if="canManage">
            <a v-if="t.status !== 'ACTIVE' && t.status !== 'ARCHIVED'" class="btn txt" @click="openTemplate(t)">编辑</a>
            <a v-if="t.status !== 'ARCHIVED'" class="btn txt" @click="openRegister(t)">回填报备</a>
            <a v-if="t.status !== 'ACTIVE'" class="btn txt dgc" @click="delTemplate(t)">删除</a>
          </td>
        </tr>
        <tr v-if="!(cfg?.templates ?? []).length">
          <td :colspan="canManage ? 7 : 6" class="note" style="text-align:center;padding:24px 0">
            暂无模板（发送时回落平台全局模板/明文）
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 发送统计 & 明细 -->
    <div class="sec-title" style="display:flex;align-items:center">
      发送统计 &amp; 明细
      <button class="btn df sm" style="margin-left:auto" @click="exportCsv">导出明细</button>
    </div>

    <div class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <input class="inp" type="date" v-model="filter.from" style="min-width:150px" @change="onFilter" />
      <span class="note" style="margin:0">~</span>
      <input class="inp" type="date" v-model="filter.to" style="min-width:150px" @change="onFilter" />
      <select class="inp" v-model="filter.status" @change="onFilter" style="min-width:130px">
        <option value="">状态：全部</option><option value="SENT">成功</option><option value="FAILED">失败</option>
      </select>
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <!-- KPI：全量口径（GET /sms-records/stats） -->
    <div class="kpis" style="grid-template-columns:repeat(auto-fit,minmax(110px,1fr));margin-bottom:10px">
      <div class="kpi"><div class="n">{{ stats.total }}</div><div class="l">发送总数</div></div>
      <div class="kpi"><div class="n" style="color:var(--success)">{{ stats.sent }}</div><div class="l">成功</div></div>
      <div class="kpi"><div class="n" style="color:var(--danger)">{{ stats.failed }}</div><div class="l">失败</div></div>
      <div class="kpi" v-for="f in (stats.failReasons ?? [])" :key="f.reason">
        <div class="n" style="color:var(--danger);font-size:14px">{{ f.count }}</div>
        <div class="l">失败·{{ f.reason }}</div>
      </div>
    </div>

    <table v-loading="loading">
      <thead><tr><th>时间</th><th v-if="!orgId && false">组织</th><th>案件</th><th>模板</th><th>状态</th><th>失败原因</th></tr></thead>
      <tbody>
        <tr v-for="s in items" :key="s.id || s.sentAt">
          <td>{{ (s.sentAt || '').slice(0, 19).replace('T', ' ') || '—' }}</td>
          <td>{{ s.caseId || '—' }}</td>
          <td>{{ s.template || '催费模板' }}</td>
          <td><span class="tag" :class="statusTag(s.status)">{{ statusName(s.status) }}</span></td>
          <td>{{ s.status === 'FAILED' ? (s.failureReason || '未知') : '—' }}</td>
        </tr>
        <tr v-if="!loading && !items.length"><td colspan="5" class="note" style="text-align:center">所选时段无发送记录</td></tr>
      </tbody>
    </table>
    <el-pagination v-if="total > size" small layout="prev, pager, next, total" :total="total"
      :page-size="size" :current-page="page" style="margin-top:8px"
      @current-change="(p: number) => { page = p; loadRecords() }" />
    <div class="note">统计与明细按所选时段联动；同案短信冷却按本组织配置；失败不退条数但记原因；微信转发不扣条数。</div>

    <!-- 编辑配置抽屉 -->
    <DsDrawer v-model="cDlg" :title="`短信配置 · ${cfg?.orgName ?? ''}`" :width="480">
      <el-form label-width="120px">
        <el-form-item label="短信签名" required>
          <el-input v-model="cForm.signName" placeholder="如【翠湖物业】（须已向运营商报备）" />
        </el-form-item>
        <el-form-item label="同案冷却(分钟)">
          <el-input-number v-model="cForm.cooldownMinutes" :min="0" :step="30" />
        </el-form-item>
        <el-form-item label="链接有效期(天)">
          <el-input-number v-model="cForm.payLinkTtlDays" :min="1" :max="90" />
        </el-form-item>
        <el-form-item label="余额预警(条)">
          <el-input-number v-model="cForm.warnThreshold" :min="0" :step="100" />
        </el-form-item>
        <el-form-item label="通道开关">
          <el-switch v-model="cForm.enabled" active-text="正常" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cDlg = false">取消</el-button>
        <el-button type="primary" :loading="cSaving" @click="submitConfig">保存</el-button>
      </template>
    </DsDrawer>

    <!-- 模板草稿抽屉 -->
    <DsDrawer v-model="tDlg" :title="tEditId ? '编辑模板草稿' : '新建模板草稿'" :width="560">
      <div class="alert info" style="margin-top:0;margin-bottom:10px">
        <span>正文用 <b>{0} {1}</b> 占位；<b>变量顺序</b>必须与向运营商报备时提交的顺序一致——顺序错位会把「张三，欠费 3600 元」发成「3600，欠费 张三 元」。</span>
      </div>
      <el-form label-width="100px">
        <el-form-item label="用途" required>
          <el-select v-model="tForm.kind" style="width:200px">
            <el-option v-for="(l, k) in KIND_LABEL" :key="k" :label="l" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板名称" required><el-input v-model="tForm.name" placeholder="如：催费短信 v2" /></el-form-item>
        <el-form-item label="模板正文" required>
          <el-input v-model="tForm.content" type="textarea" :rows="3" placeholder="您有一笔物业费待缴，请点击缴费：{0}" />
        </el-form-item>
        <el-form-item label="变量顺序">
          <el-select v-model="tForm.varOrder" multiple style="width:100%" placeholder="按 {0} {1} 的顺序选择">
            <el-option v-for="v in VARS" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tDlg = false">取消</el-button>
        <el-button type="primary" :loading="tSaving" @click="submitTemplate">保存草稿</el-button>
      </template>
    </DsDrawer>

    <!-- 回填报备结果抽屉 -->
    <DsDrawer v-model="rDlg" :title="`回填报备结果 · ${rRow?.name ?? ''}`" :width="480">
      <div class="alert info" style="margin-top:0;margin-bottom:10px">
        <span>报备为<b>线下动作</b>：平台向运营商提交签名+正文，审核通过后拿到模板ID，在此回填即生效（同用途旧模板自动归档）。</span>
      </div>
      <el-form label-width="120px">
        <el-form-item label="报备结果">
          <el-radio-group v-model="rForm.result">
            <el-radio-button label="ACTIVE">通过·生效</el-radio-button>
            <el-radio-button label="REJECTED">驳回</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="rForm.result === 'ACTIVE'" label="运营商模板ID" required>
          <el-input v-model="rForm.gatewayTemplateId" placeholder="运营商审核通过后下发的模板ID" />
        </el-form-item>
        <el-form-item v-else label="驳回原因">
          <el-input v-model="rForm.reason" type="textarea" :rows="2" placeholder="运营商给出的驳回原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rDlg = false">取消</el-button>
        <el-button type="primary" :loading="rSaving" @click="submitRegister">确认回填</el-button>
      </template>
    </DsDrawer>
  </div>
</template>
