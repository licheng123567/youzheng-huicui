<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M4 催收完整作业台：概览/联系人 · 通话AI · 跟进承诺工单 · 回款减免 · 法务存证。动作按 /me 权限门控。
const route = useRoute(); const router = useRouter(); const auth = useAuth()
const id = String(route.params.id)
const d = ref<any>(null)

// M-01: availableActions SSOT — 后端按当前主体权限返回可用操作点(契约 CaseDetail.availableActions)
// 动作名 → availableActions key 映射(契约操作点命名)
const ACTION_KEYS: Record<string, string> = {
  follow: 'follow', promise: 'promise', ticket: 'ticket', paylink: 'paylink',
  repay: 'repay', legal: 'legal', evidence: 'evidence', release: 'release',
  'return': 'return', close: 'close'
}
const availableActions = computed<string[]>(function() { return d.value && d.value.availableActions ? d.value.availableActions : [] })
// 按钮显隐：availableActions 非空时以其为 SSOT；为空时回退纯权限判断(后端未返时全不隐)
function canAct(actionKey: string, permission: string): boolean {
  var actions = availableActions.value
  if (actions.length === 0) return auth.has(permission)
  return auth.has(permission) && actions.indexOf(actionKey) !== -1
}

// M-01: 通话结果标记码 SSOT — 改读 CaseDetail.markCodes(后端按 settings/CFG-MARK-CODES 下发可见启用项，绕开 platform-scoped /settings)。
// 无来源时回退本地兜底常量(防 d 未加载/老后端)。
const MARK_CODES_FALLBACK: Array<{ code: string; label: string }> = [
  { code: 'PROMISED', label: '已承诺' },
  { code: 'REFUSED', label: '拒接/拒还' },
  { code: 'NEED_TICKET', label: '需转工单' },
  { code: 'FOLLOW_UP', label: '待跟进' },
  { code: 'NO_ANSWER', label: '无人接听' }
]
// 仅取 enabled 项；后端未下发 markCodes 则回退兜底
const markCodes = computed<Array<{ code: string; label: string }>>(function () {
  var src = d.value && d.value.markCodes ? d.value.markCodes : []
  var enabled = src.filter(function (m: any) { return m && m.enabled !== false && m.code })
    .map(function (m: any) { return { code: m.code, label: m.label || m.code } })
  return enabled.length ? enabled : MARK_CODES_FALLBACK
})

// H-05: 结案脱敏收敛 — redacted=true 且当前主体非平台(SA/SE)时，概览/联系人切统计视图、不渲染逐行明细。
// 平台(SA/SE)与持有物业仍可见明细(脱敏由后端按 scope 决定，此处仅控收敛展示)。
const isPlatform = computed<boolean>(function () { return auth.me?.org?.type === 'PLATFORM' })
const redacted = computed<boolean>(function () { return !!(d.value && d.value.case && d.value.case.redacted) })
// 统计收敛态：脱敏 且 非平台 → 概览/联系人不渲染明细，改渲染统计卡
const summaryView = computed<boolean>(function () { return redacted.value && !isPlatform.value })
// 统计卡聚合(前端聚合，无需后端 summary)
const stat = computed<any>(function () {
  var settled = repays.value.filter(function (r: any) { return !r.reversed }).reduce(function (s: number, r: any) { return s + (r.amountCents || 0) }, 0)
  return {
    dueCents: d.value?.case?.dueCents,
    reduceAfterCents: d.value?.case?.reduceAfterCents ?? d.value?.case?.dueCents,
    repaidCents: settled,
    promiseCount: promises.value.length,
    ticketCount: tickets.value.length,
    contactCount: (d.value?.contacts ?? []).length
  }
})
const promises = ref<any[]>([]); const tickets = ref<any[]>([]); const legalDocs = ref<any[]>([]); const repays = ref<any[]>([])
const readyRecs = ref<any[]>([])   // H-02: 本案 READY 录音(供 RECORDING 存证选 refIds)
const payLinks = ref<any[]>([])   // 本会话创建的缴费链接(契约无 per-case 列表端点,发后捕获→可重发/作废 BR-M4-14)
const latest = ref<any>(null); const review = ref<any>(null)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

async function loadAll() {
  const det = await api.GET('/cases/{id}', { params: { path: { id } } })
  if (det.error) { ElMessage.error('加载失败'); return }
  d.value = det.data
  promises.value = ((await api.GET('/cases/{id}/promises', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  tickets.value = ((await api.GET('/cases/{id}/tickets', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  legalDocs.value = ((await api.GET('/cases/{id}/legal-docs', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  // H-02: 本案录音列表(供存证 RECORDING 场景选 READY 录音 refIds)
  const recs = ((await api.GET('/recordings', { params: { query: { caseId: id, page: 1, size: 50 } } } as any)).data as any)?.items ?? []
  readyRecs.value = recs.filter((x: any) => x.status === 'READY')
  // 回款明细经批次端点过滤本案（无独立 per-case 列表端点）
  const bid = d.value?.case?.batchId
  if (bid) {
    const rl = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: String(bid) }, query: { page: 1, size: 100 } } } as any)
    repays.value = ((rl.data as any)?.items ?? []).filter((x: any) => String(x.caseId) === String(d.value?.case?.id))
  }
}
async function getLatest() {
  const { data, error } = await api.GET('/cases/{id}/recordings/latest', { params: { path: { id } } })
  if (error) { ElMessage.error('获取失败'); return }
  latest.value = data
  if (!(data as any)?.hasRecording) ElMessage.info('暂无录音（App 通话结束自动上传 / 无则手动上传）')
}
async function loadReview(recId: string) {
  const { data, error } = await api.GET('/recordings/{id}/ai-review', { params: { path: { id: recId } } })
  if (error) { ElMessage.error('复盘加载失败'); return }
  review.value = data
}
// 录音：上传 / 解析 / 结果标记
async function uploadRecording(e: any) {
  const file = e.target.files?.[0]; if (!file) return
  const fd = new FormData(); fd.append('file', file)
  const r = await fetch(`/v1/cases/${id}/recordings`, { method: 'POST', headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }, body: fd })
  if (!r.ok) { ElMessage.error('上传失败 ' + r.status); return }
  ElMessage.success('已上传，解析中'); getLatest()
}
// FAILED 走 /reprocess(重处理)；parse 仅 QUOTA_BLOCKED/READY 补解析(BR-M5-02/08)
async function reprocessRec(recId: string) {
  const { error } = await api.POST('/recordings/{id}/reprocess', { params: { path: { id: recId } } } as any)
  if (error) { ElMessage.error('重处理失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已触发重处理'); getLatest()
}
// H-06: QUOTA_BLOCKED 单条补解析(充值后手动补触发 ASR BR-M5-02)；余额不足 409 BIZ_QUOTA_EXHAUSTED 提示充值
async function parseRec(recId: string) {
  const { error } = await api.POST('/recordings/{id}/parse', { params: { path: { id: recId } } } as any)
  if (error) {
    var code = (error as any)?.code ?? (error as any)?.error?.code
    if (code === 'BIZ_QUOTA_EXHAUSTED') { ElMessage.warning('解析余额不足，请先充值解析分钟后再补解析'); return }
    ElMessage.error('补解析失败：' + ((error as any)?.message ?? '')); return
  }
  ElMessage.success('已受理补解析（解析中）'); getLatest()
}
// H-06: 批量补解析(按本案 caseId 过滤待解析录音，余额扣完为止 BR-M5-02)
async function batchParseRec() {
  const { data, error } = await api.POST('/recordings/batch-parse', { body: { caseIds: [id] } as any })
  if (error) {
    var code = (error as any)?.code ?? (error as any)?.error?.code
    if (code === 'BIZ_QUOTA_EXHAUSTED') { ElMessage.warning('解析余额不足，请先充值后再批量补解析'); return }
    ElMessage.error('批量补解析失败：' + ((error as any)?.message ?? '')); return
  }
  var r = data as any
  ElMessage.success('已受理批量补解析：入队 ' + (r?.queued ?? 0) + ' 条' + (r?.skipped ? ('，余额不足跳过 ' + r.skipped + ' 条') : ''))
  getLatest()
}
const mkdlg = ref(false); const mkForm = ref<any>({})
function openMark(recId: string) { mkForm.value = { recId, mark: markCodes.value[0]?.code ?? 'PROMISED' }; mkdlg.value = true }
async function submitMark() {
  const { error } = await api.POST('/recordings/{id}/ai-review', { params: { path: { id: mkForm.value.recId } }, body: { mark: mkForm.value.mark } as any })
  if (error) { ElMessage.error('标记失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已标记通话结果'); mkdlg.value = false
}

// 通用动作对话框（跟进/承诺/工单/缴费/结案/还款/法务/存证）
const dlg = ref<{ open: boolean; kind: string; title: string }>({ open: false, kind: '', title: '' })
const form = ref<any>({})
// H-02: 存证 refIds 候选 — RECORDING 用本案 READY 录音；DELIVERY 用本案 SIGNED 法律文书；MATERIAL_PACK 可空
const evidenceRefOptions = computed<Array<{ value: string; label: string }>>(function () {
  var scene = form.value && form.value.scene
  if (scene === 'RECORDING') return readyRecs.value.map(function (r: any) { return { value: String(r.id), label: '录音#' + r.id + ' · ' + (r.durationSec || 0) + 's' } })
  if (scene === 'DELIVERY') return legalDocs.value.filter(function (l: any) { return l.status === 'SIGNED' }).map(function (l: any) { return { value: String(l.id), label: l.type + ' #' + l.id } })
  return []
})
function openAct(kind: string, title: string, sourceSuggestionId?: string) {
  form.value = kind === 'follow' ? { content: '', method: 'CALL', attachments: [], sourceSuggestionId }
    : kind === 'promise' ? { date: '', amountYuan: 0, installments: [], sourceSuggestionId }
    : kind === 'ticket' ? { type: '上门核实', note: '', sourceSuggestionId }
    : kind === 'close' ? { closeKind: 'WITHDRAWN', reason: '' }
    : kind === 'repay' ? { amountYuan: 0, channel: 'WECHAT_QR', paidAt: '' }
    : kind === 'legal' ? { type: 'COLLECTION_LETTER' }
    : kind === 'evidence' ? { scene: 'RECORDING', note: '', refIds: [] }
    : { channel: 'SMS', sourceSuggestionId }
  dlg.value = { open: true, kind, title }
}
// follow 跟进附件增删(name+url)
function addAttachment() { (form.value.attachments ||= []).push({ name: '', url: '' }) }
function removeAttachment(i: number) { form.value.attachments.splice(i, 1) }
function addInstallment() { (form.value.installments ||= []).push({ seq: form.value.installments.length + 1, dueDate: '', amountYuan: 0 }) }
async function submitAct() {
  const k = dlg.value.kind, f = form.value
  let res: any
  if (k === 'follow') {
    // M-08: 跟进携带附件(过滤掉空行)
    const atts = (f.attachments || []).filter((a: any) => a && (a.name || a.url))
    res = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: f.content, method: f.method, attachments: atts.length ? atts : undefined, sourceSuggestionId: f.sourceSuggestionId } as any })
  }
  else if (k === 'promise') {
    const inst = (f.installments || []).map((x: any) => ({ seq: x.seq, dueDate: x.dueDate, amountCents: Math.round(x.amountYuan * 100) }))
    res = await api.POST('/cases/{id}/promises', { params: { path: { id } }, body: { date: f.date, amountCents: Math.round(f.amountYuan * 100), installments: inst.length ? inst : undefined, sourceSuggestionId: f.sourceSuggestionId } as any })
  }
  else if (k === 'ticket') res = await api.POST('/cases/{id}/tickets', { params: { path: { id } }, body: { type: f.type, note: f.note, sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'close') res = await api.POST('/cases/{id}/close', { params: { path: { id } }, body: { kind: f.closeKind, reason: f.reason } as any })
  else if (k === 'repay') res = await api.POST('/cases/{id}/repay-lines', { params: { path: { id } }, body: { amountCents: Math.round(f.amountYuan * 100), channel: f.channel, paidAt: f.paidAt } as any })
  else if (k === 'legal') res = await api.POST('/cases/{id}/legal-docs', { params: { path: { id } }, body: { type: f.type } as any })
  else if (k === 'evidence') {
    // H-02: RECORDING/DELIVERY 必带 refIds(指向 READY 录音/SIGNED 文书)，否则后端 422
    if ((f.scene === 'RECORDING' || f.scene === 'DELIVERY') && !(f.refIds && f.refIds.length)) {
      ElMessage.error(f.scene === 'RECORDING' ? '录音存证需选择至少一条 READY 录音' : '送达存证需选择至少一份 SIGNED 文书'); return
    }
    res = await api.POST('/cases/{id}/evidence', { params: { path: { id } }, body: { scene: f.scene, note: f.note, refIds: (f.refIds && f.refIds.length) ? f.refIds : undefined } as any })
  }
  else res = await api.POST('/cases/{id}/pay-links', { params: { path: { id } }, body: { channel: f.channel, sourceSuggestionId: f.sourceSuggestionId } as any })
  if (res.error) { ElMessage.error('提交失败：' + ((res.error as any)?.message ?? '')); return }
  if (k === 'paylink' && res.data) payLinks.value.unshift(res.data)   // 捕获新链接→可重发/作废
  ElMessage.success(dlg.value.title + '成功'); dlg.value.open = false; loadAll()
}
// 缴费链接 重发/作废（BR-M4-14；link id 来自创建响应，契约无 per-case 列表端点）
async function resendLink(l: any) {
  const { error } = await api.POST('/pay-links/{id}/resend', { params: { path: { id: String(l.id) } } } as any)
  if (error) { ElMessage.error('重发失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已重发')
}
async function voidLink(l: any) {
  const { error } = await api.POST('/pay-links/{id}/void', { params: { path: { id: String(l.id) } } } as any)
  if (error) { ElMessage.error('作废失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已作废'); l.status = 'EXPIRED'   // 契约 PayLinkStatusEnum=ACTIVE/EXPIRED（无 VOIDED）
}
// 工单处理
const hdlg = ref(false); const hForm = ref<any>({})
function openHandle(t: any) { hForm.value = { id: t.id, result: '', receipt: '' }; hdlg.value = true }
async function submitHandle() {
  const { error } = await api.POST('/tickets/{id}/handle', { params: { path: { id: hForm.value.id } }, body: { result: hForm.value.result, receipt: hForm.value.receipt } as any })
  if (error) { ElMessage.error('处理失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('工单已处理'); hdlg.value = false; loadAll()
}
// 回款冲销
async function reverseRepay(r: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt('冲销原因（误标红冲 BR-M4-07）', '冲销回款', { inputValidator: (v) => !!v || '原因必填' })
    const { error } = await api.POST('/repay-lines/{id}/reverse', { params: { path: { id: r.id } }, body: { reason } as any })
    if (error) { ElMessage.error('冲销失败：' + ((error as any)?.message ?? '已入支付申请单不可冲')); return }
    ElMessage.success('已冲销'); loadAll()
  } catch { /* 取消 */ }
}
// 联系人 add / 失效 / 设主号 — M-08：主号(isPrimary)显示+可设、标签可选、新增时可勾主号
const cdlg = ref(false); const cForm = ref<any>({})
function openAddContact() { cForm.value = { phone: '', label: '本人', isPrimary: false }; cdlg.value = true }
async function submitContact() {
  if (!/^\d{6,}$/.test(cForm.value.phone)) { ElMessage.error('请输入有效号码'); return }
  const { error } = await api.POST('/cases/{id}/contacts', { params: { path: { id } }, body: { phone: cForm.value.phone, label: cForm.value.label, isPrimary: cForm.value.isPrimary } as any })
  if (error) { ElMessage.error('新增失败'); return }
  ElMessage.success('已新增联系人'); cdlg.value = false; loadAll()
}
// 设主号：PATCH isPrimary:true(后端维护单一主号约束，旧主号降级)
async function setPrimaryContact(c: any) {
  const { error } = await api.PATCH('/contacts/{id}', { params: { path: { id: String(c.id) } }, body: { isPrimary: true } as any })
  if (error) { ElMessage.error('设主号失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已设为主号'); loadAll()
}
async function invalidContact(c: any) {
  const { error } = await api.PATCH('/contacts/{id}', { params: { path: { id: String(c.id) } }, body: { invalid: true } as any })
  if (error) { ElMessage.error('标记失效失败'); return }
  ElMessage.success('已标记失效'); loadAll()
}
// 法务送达
async function deliverLegal(doc: any) {
  const { error } = await api.POST('/legal-docs/{id}/deliver', { params: { path: { id: String(doc.id) } }, body: { signedPhotoUrl: 'https://example.com/sign.jpg' } as any })
  if (error) { ElMessage.error('送达登记失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已登记送达'); loadAll()
}
// US-M4-07 建议法务轻标：催收员标记"建议走法务"——不改状态、不出私海，落跟进记录留痕(轻标,区别于协调员正式法务申请)
async function suggestLegal() {
  const { error } = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: '【建议走法务】催收员建议本案进入法务程序（轻标·待协调员审）', method: 'OTHER' } as any })
  if (error) { ElMessage.error('建议失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已轻标"建议走法务"（记入跟进，待协调员法务申请）'); loadAll()
}
// 生命周期：释放(CO)/退回(VL)——带原因，状态机 CAS
async function lifecycle(verb: string, path: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt(verb + '原因', verb + '案件', { inputValidator: (v) => !!v || '原因必填' })
    const { error } = await api.POST(path, { params: { path: { id } }, body: { reason } as any })
    if (error) { ElMessage.error(`${verb}失败：${(error as any)?.message ?? ''}`); return }
    ElMessage.success(`已${verb}`); loadAll()
  } catch { /* 取消 */ }
}
// AI 采纳联动（校验动作权限）
const ADOPT: any = { PROMISE: ['promise', '登记承诺', 'case.promise'], TICKET: ['ticket', '转工单', 'case.ticket'], PAYLINK: ['paylink', '发缴费链接', 'case.paylink'], FOLLOWUP: ['follow', '写跟进', 'case.follow'] }
function adopt(card: any) {
  const m = ADOPT[card.actionRef]; if (!m) { ElMessage.info('该建议无联动动作'); return }
  if (!auth.has(m[2])) { ElMessage.warning('无权限：' + m[2]); return }
  openAct(m[0], m[1] + '（采纳 AI 建议）', card.id)
}
onMounted(loadAll)
</script>

<template>
  <div v-if="d">
    <el-page-header @back="router.back()" :content="`案件作业台：${d.case?.ownerName} ${d.case?.room} · ${d.case?.status}`" style="margin-bottom:12px" />
    <el-card style="margin-bottom:12px">
      <!-- M-01: availableActions(CaseDetail.availableActions) 非空时作 SSOT；为空回退纯权限(BR-M8-04) -->
      <el-button v-if="canAct('follow','case.follow')" type="primary" size="small" @click="openAct('follow','写跟进')">写跟进</el-button>
      <el-button v-if="canAct('promise','case.promise')" size="small" @click="openAct('promise','登记承诺')">登记承诺</el-button>
      <el-button v-if="canAct('ticket','case.ticket')" size="small" @click="openAct('ticket','转工单')">转工单</el-button>
      <el-button v-if="canAct('paylink','case.paylink')" size="small" @click="openAct('paylink','发缴费链接')">发缴费链接</el-button>
      <el-button v-if="canAct('repay','case.repay.mark')" size="small" type="success" @click="openAct('repay','登记还款')">登记还款</el-button>
      <el-button v-if="canAct('follow','case.follow')" size="small" @click="suggestLegal">建议走法务</el-button>
      <el-button v-if="canAct('legal','legal.create')" size="small" @click="openAct('legal','申请法务文书')">申请法务</el-button>
      <el-button v-if="canAct('evidence','evidence.create')" size="small" @click="openAct('evidence','发起存证')">发起存证</el-button>
      <el-button v-if="canAct('release','case.release')" size="small" @click="lifecycle('释放','/cases/{id}/release')">释放</el-button>
      <el-button v-if="canAct('return','case.return')" size="small" @click="lifecycle('退回','/cases/{id}/return')">退回</el-button>
      <el-button v-if="canAct('close','case.close')" size="small" type="danger" plain @click="openAct('close','结案')">结案</el-button>
    </el-card>

    <el-tabs>
      <el-tab-pane label="概览 / 联系人">
        <!-- H-05: 结案脱敏(redacted)且非平台 → 统计收敛视图，不渲染逐行明细 -->
        <template v-if="summaryView">
          <el-alert type="info" :closable="false" style="margin-bottom:10px" title="本案已结案并脱敏（BR-M8-09）：仅展示统计汇总，业主姓名/联系方式等明细已收敛。" />
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="户号">{{ d.case?.acctNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ d.case?.status }}</el-descriptions-item>
            <el-descriptions-item label="池">{{ d.case?.pool }}</el-descriptions-item>
          </el-descriptions>
          <el-row :gutter="12" style="margin-top:12px">
            <el-col :span="6"><el-statistic title="应收" :value="(stat.dueCents ?? 0)/100" :precision="2" prefix="¥" /></el-col>
            <el-col :span="6"><el-statistic title="减免后" :value="(stat.reduceAfterCents ?? 0)/100" :precision="2" prefix="¥" /></el-col>
            <el-col :span="6"><el-statistic title="已回款合计" :value="stat.repaidCents/100" :precision="2" prefix="¥" /></el-col>
            <el-col :span="6"><el-statistic title="联系方式数" :value="stat.contactCount" /></el-col>
          </el-row>
          <el-row :gutter="12" style="margin-top:12px">
            <el-col :span="6"><el-statistic title="承诺数" :value="stat.promiseCount" /></el-col>
            <el-col :span="6"><el-statistic title="工单数" :value="stat.ticketCount" /></el-col>
          </el-row>
        </template>
        <template v-else>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="户号">{{ d.case?.acctNo }}</el-descriptions-item>
          <el-descriptions-item label="应收">{{ yuan(d.case?.dueCents) }}</el-descriptions-item>
          <el-descriptions-item label="减免后">{{ yuan(d.case?.reduceAfterCents ?? d.case?.dueCents) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ d.case?.status }}</el-descriptions-item>
          <el-descriptions-item label="池">{{ d.case?.pool }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">联系方式 <el-button v-if="auth.has('case.follow')" size="small" text type="primary" @click="openAddContact">+ 新增</el-button></el-divider>
        <el-table :data="d.contacts ?? []" size="small" border>
          <el-table-column prop="phone" label="电话" /><el-table-column prop="label" label="标签" />
          <el-table-column label="主号" width="70"><template #default="{row}"><el-tag v-if="row.isPrimary" size="small" type="warning">主号</el-tag></template></el-table-column>
          <el-table-column label="状态"><template #default="{row}"><el-tag size="small" :type="row.invalid?'info':'success'">{{ row.invalid?'失效':'有效' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="150"><template #default="{row}">
            <el-button v-if="!row.invalid && !row.isPrimary && auth.has('case.follow')" size="small" text type="primary" @click="setPrimaryContact(row)">设主号</el-button>
            <el-button v-if="!row.invalid && auth.has('case.follow')" size="small" text @click="invalidContact(row)">标失效</el-button>
          </template></el-table-column>
        </el-table>
        </template>
        <template v-if="payLinks.length">
          <el-divider content-position="left">缴费链接（本会话创建 · BR-M4-14 重发/作废）</el-divider>
          <el-table :data="payLinks" size="small" border>
            <el-table-column prop="token" label="token" /><el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{row}"><el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{ row.status==='ACTIVE'?'有效':'已失效' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="160"><template #default="{row}">
              <el-button v-if="row.status==='ACTIVE' && auth.has('case.paylink')" size="small" @click="resendLink(row)">重发</el-button>
              <el-button v-if="row.status==='ACTIVE' && auth.has('case.paylink')" size="small" text type="danger" @click="voidLink(row)">作废</el-button>
            </template></el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="通话 / AI 复盘">
        <el-button size="small" v-if="auth.has('case.call')" @click="getLatest">获取最新通话录音</el-button>
        <el-button size="small" v-if="auth.has('case.call')" tag="label" style="margin-left:6px">上传录音<input type="file" hidden accept="audio/*" @change="uploadRecording" /></el-button>
        <template v-if="latest?.hasRecording && latest.recording">
          <el-descriptions :column="3" border size="small" style="margin-top:8px">
            <el-descriptions-item label="状态">{{ latest.recording.status }}</el-descriptions-item>
            <el-descriptions-item label="时长">{{ latest.recording.durationSec }}s</el-descriptions-item>
            <el-descriptions-item label="操作">
              <el-button size="small" type="primary" @click="loadReview(latest.recording.id)">看 AI 复盘</el-button>
              <el-button size="small" @click="router.push(`/cases/${id}/call/${latest.recording.id}`)">通话记录详情</el-button>
              <el-button v-if="latest.recording.status==='FAILED'" size="small" @click="reprocessRec(latest.recording.id)">重新处理</el-button>
              <!-- H-06: QUOTA_BLOCKED 充值后补解析(单条 parse + 批量 batch-parse) -->
              <el-button v-if="latest.recording.status==='QUOTA_BLOCKED' && auth.has('case.call')" size="small" type="warning" @click="parseRec(latest.recording.id)">补解析</el-button>
              <el-button v-if="latest.recording.status==='QUOTA_BLOCKED' && auth.has('case.call')" size="small" @click="batchParseRec">批量补解析</el-button>
              <!-- H-05: 标记结果调 POST /recordings/{id}/ai-review x-permission=case.follow，非 case.call -->
              <el-button v-if="auth.has('case.follow')" size="small" @click="openMark(latest.recording.id)">标记结果</el-button>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="latest.recording.status==='QUOTA_BLOCKED'" type="warning" :closable="false" style="margin-top:8px" title="解析余额不足已暂停（BR-M5-02）：充值解析分钟后点「补解析」按时间顺序续解析。" />
        </template>
        <template v-if="review">
          <el-divider>AI 复盘</el-divider>
          <p><b>小结：</b>{{ review.summary }}</p>
          <!-- M-02: 说话人分离对话气泡(review.dialogue) -->
          <template v-if="review.dialogue?.length">
            <div style="max-height:260px;overflow:auto;background:#f5f7fa;padding:8px;border-radius:4px;margin:6px 0">
              <div v-for="(turn,ti) in review.dialogue" :key="ti" style="display:flex;margin:4px 0" :style="{ justifyContent: turn.speaker==='AGENT' || turn.speaker==='催收员' ? 'flex-end' : 'flex-start' }">
                <div :style="{ maxWidth:'72%', background: turn.speaker==='AGENT' || turn.speaker==='催收员' ? '#d9ecff' : '#fff', border:'1px solid #e4e7ed', borderRadius:'6px', padding:'6px 10px' }">
                  <div style="font-size:12px;color:#909399">{{ turn.speaker }}</div>
                  <div style="font-size:13px;white-space:pre-wrap">{{ turn.text }}</div>
                </div>
              </div>
            </div>
          </template>
          <!-- M-02: 风险标签追加 segmentTs 片段定位 -->
          <p v-if="review.risks?.length"><b>风险：</b><el-tag v-for="(r,ri) in review.risks" :key="ri" type="danger" size="small" style="margin:2px">{{ r.level }} {{ r.desc }}<span v-if="r.segmentTs"> @{{ r.segmentTs }}</span></el-tag></p>
          <el-card v-for="su in review.suggestions ?? []" :key="su.id" shadow="never" style="margin:6px 0">
            <b>{{ su.title }}</b> <el-tag size="small">{{ su.type }}</el-tag>
            <div style="color:#606266;font-size:13px">{{ su.body }}</div>
            <el-button v-if="su.actionRef && su.actionRef!=='NONE'" size="small" type="primary" text @click="adopt(su)">采纳 → {{ su.actionRef }}</el-button>
          </el-card>
        </template>
      </el-tab-pane>

      <el-tab-pane label="承诺 / 工单">
        <el-divider content-position="left">承诺（含分期）</el-divider>
        <el-table :data="promises" size="small" border>
          <el-table-column prop="date" label="日期" /><el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
          <el-table-column prop="state" label="状态" /><el-table-column label="分期"><template #default="{row}">{{ row.installments?.length ? row.installments.length+' 期' : '单笔' }}</template></el-table-column>
        </el-table>
        <el-divider content-position="left">工单</el-divider>
        <el-table :data="tickets" size="small" border>
          <el-table-column prop="type" label="类型" /><el-table-column prop="note" label="说明" />
          <el-table-column prop="status" label="状态" width="90" /><el-table-column prop="result" label="结果" />
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="row.status==='PENDING' && auth.has('ticket.handle')" size="small" type="primary" @click="openHandle(row)">处理</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="回款">
        <el-table :data="repays" size="small" border>
          <el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
          <el-table-column prop="channel" label="渠道" /><el-table-column prop="paidAt" label="日期" />
          <el-table-column label="状态"><template #default="{row}"><el-tag size="small" :type="row.reversed?'info':(row.settled?'success':'warning')">{{ row.reversed?'已冲销':(row.settled?'已结':'未结') }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="!row.reversed && auth.has('case.repay.mark')" size="small" text type="danger" @click="reverseRepay(row)">冲销</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="事件时间线">
        <el-timeline>
          <el-timeline-item v-for="ev in d.timeline ?? []" :key="ev.id" :timestamp="ev.createdAt" placement="top">
            <el-tag size="small">{{ ev.type }}</el-tag> {{ ev.content }} <span style="color:#909399">· {{ ev.actor }}</span>
          </el-timeline-item>
          <el-empty v-if="!(d.timeline?.length)" description="暂无事件" :image-size="50" />
        </el-timeline>
      </el-tab-pane>

      <el-tab-pane label="法务 / 存证">
        <el-table :data="legalDocs" size="small" border>
          <el-table-column prop="type" label="文书类型" /><el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="deliveredAt" label="送达时间" />
          <el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="row.status!=='DELIVERED' && auth.has('legal.create')" size="small" @click="deliverLegal(row)">登记送达</el-button></template></el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:8px" title="存证：通过上方「发起存证」按场景(录音/送达/材料包)发起；列表与验真见「存证」菜单。" />
      </el-tab-pane>
    </el-tabs>

    <!-- 通用动作对话框 -->
    <el-dialog v-model="dlg.open" :title="dlg.title" width="500px">
      <el-form label-width="90px">
        <template v-if="dlg.kind==='follow'">
          <el-form-item label="方式"><el-select v-model="form.method"><el-option v-for="m in ['CALL','SMS','VISIT','WECHAT','OTHER']" :key="m" :label="m" :value="m" /></el-select></el-form-item>
          <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="3" /></el-form-item>
          <!-- M-08: 跟进附件(name+url) -->
          <el-form-item label="附件">
            <el-button size="small" @click="addAttachment">+ 加附件</el-button>
            <div v-for="(a,ai) in form.attachments" :key="ai" style="margin-top:4px;display:flex;gap:4px;align-items:center">
              <el-input v-model="a.name" size="small" placeholder="名称" style="width:120px" />
              <el-input v-model="a.url" size="small" placeholder="url" style="width:200px" />
              <el-button size="small" text type="danger" @click="removeAttachment(ai)">删</el-button>
            </div>
          </el-form-item>
        </template>
        <template v-else-if="dlg.kind==='promise'">
          <el-form-item label="承诺日期"><el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
          <el-form-item label="分期">
            <el-button size="small" @click="addInstallment">+ 加一期</el-button>
            <div v-for="(it,i) in form.installments" :key="i" style="margin-top:4px">
              第{{ it.seq }}期 <el-date-picker v-model="it.dueDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:140px" /> <el-input-number v-model="it.amountYuan" :min="0" size="small" />
            </div>
          </el-form-item>
        </template>
        <template v-else-if="dlg.kind==='ticket'">
          <el-form-item label="类型"><el-input v-model="form.type" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='repay'">
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option v-for="c in ['WECHAT_QR','BANK_TRANSFER','CASH']" :key="c" :label="c" :value="c" /></el-select></el-form-item>
          <el-form-item label="到账日"><el-date-picker v-model="form.paidAt" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='legal'">
          <el-form-item label="文书类型"><el-select v-model="form.type"><el-option label="催款函" value="COLLECTION_LETTER" /><el-option label="律师函" value="LAWYER_LETTER" /><el-option label="诉讼" value="LITIGATION" /></el-select></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='evidence'">
          <el-form-item label="存证场景"><el-select v-model="form.scene" @change="form.refIds=[]"><el-option label="录音" value="RECORDING" /><el-option label="送达" value="DELIVERY" /><el-option label="材料包" value="MATERIAL_PACK" /></el-select></el-form-item>
          <!-- H-02: RECORDING/DELIVERY 必选 refIds(READY 录音 / SIGNED 文书)；MATERIAL_PACK 可留空 -->
          <el-form-item v-if="form.scene==='RECORDING' || form.scene==='DELIVERY'" :label="form.scene==='RECORDING' ? '选录音' : '选文书'">
            <el-select v-model="form.refIds" multiple style="width:100%" :placeholder="evidenceRefOptions.length ? '请选择' : (form.scene==='RECORDING' ? '本案无 READY 录音' : '本案无 SIGNED 文书')">
              <el-option v-for="o in evidenceRefOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='close'">
          <el-form-item label="结案类型"><el-select v-model="form.closeKind"><el-option label="撤案" value="WITHDRAWN" /><el-option label="坏账" value="BAD_DEBT" /></el-select></el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option label="短信" value="SMS" /><el-option label="微信转发" value="WECHAT_COPY" /></el-select></el-form-item>
        </template>
        <el-form-item v-if="form.sourceSuggestionId"><el-tag type="success">采纳 AI 建议 #{{ form.sourceSuggestionId }}</el-tag></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg.open=false">取消</el-button><el-button type="primary" @click="submitAct">提交</el-button></template>
    </el-dialog>

    <!-- 工单处理 -->
    <el-dialog v-model="hdlg" title="处理工单（POST /tickets/{id}/handle）" width="440px">
      <el-form label-width="80px">
        <el-form-item label="处理结果"><el-input v-model="hForm.result" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="回执"><el-input v-model="hForm.receipt" placeholder="回执地址/说明" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="hdlg=false">取消</el-button><el-button type="primary" @click="submitHandle">提交</el-button></template>
    </el-dialog>

    <!-- 通话结果标记 -->
    <!-- M-01: 标记码 SSOT 来自 CaseDetail.markCodes(enabled 项)，无来源回退 MARK_CODES_FALLBACK -->
    <el-dialog v-model="mkdlg" title="通话结果标记（POST /recordings/{id}/ai-review）" width="400px">
      <el-form label-width="80px"><el-form-item label="结果码"><el-select v-model="mkForm.mark"><el-option v-for="m in markCodes" :key="m.code" :label="m.label" :value="m.code" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="mkdlg=false">取消</el-button><el-button type="primary" @click="submitMark">标记</el-button></template>
    </el-dialog>

    <!-- M-08: 新增联系人(标签 + 主号) -->
    <el-dialog v-model="cdlg" title="新增联系人（POST /cases/{id}/contacts）" width="400px">
      <el-form label-width="80px">
        <el-form-item label="电话"><el-input v-model="cForm.phone" placeholder="联系号码" /></el-form-item>
        <el-form-item label="标签"><el-select v-model="cForm.label"><el-option v-for="l in ['本人','配偶','亲属','单位','补充']" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="设为主号"><el-switch v-model="cForm.isPrimary" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitContact">提交</el-button></template>
    </el-dialog>
  </div>
</template>
