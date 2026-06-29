<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { caseStatusLabel, poolLabel, promiseStateLabel, callRecStatusLabel, legalDocTypeLabel, legalDocStatusLabel, legalStageLabel } from '../constants/enums'

// M4 催收三栏接打台：左画像 / 中三Tab(沟通记录·项目资料·作战手册) / 右操作区。动作按 /me 权限门控。
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
const playbookDoc = ref<any>(null)   // 项目/批次作战手册静态底稿(容错取其一)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// ===== 纯展示辅助（仅 UI 表现层，不参与数据流）=====
// 案件状态 → ds-admin .tag 配色
const CASE_STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan'
}
const caseStatusTag = (s?: string) => CASE_STATUS_TAG[s ?? ''] ?? 'inf'
// 录音/通用通道状态 → 配色
const REC_STATUS_TAG: Record<string, string> = {
  READY: 'suc', PROCESSING: 'pri', PARSING: 'pri',
  FAILED: 'dan', QUOTA_BLOCKED: 'war', UPLOADED: 'inf'
}
const recStatusTag = (s?: string) => REC_STATUS_TAG[s ?? ''] ?? 'inf'
// 业主姓名首字（画像头像）
const ownerInitial = computed<string>(function () {
  var n = d.value?.case?.ownerName
  return n ? String(n).charAt(0) : '案'
})
// 时间线类型 → ds-admin .ty-xxx class（按 item.type 小写；缺省落 ty-note）
const TL_TY: Record<string, string> = {
  CALL: 'ty-call', NOTE: 'ty-note', FOLLOWUP: 'ty-note', FOLLOW_UP: 'ty-note',
  TICKET: 'ty-ticket', SMS: 'ty-sms', PAYLINK: 'ty-sms', PROMISE: 'ty-promise',
  STATUS: 'ty-status', OPLOG: 'ty-status', LEGAL: 'ty-legal', EVIDENCE: 'ty-evidence',
  REPAY: 'ty-promise'
}
const tlTy = (t?: string) => TL_TY[String(t ?? '').toUpperCase()] ?? 'ty-note'
// 时间线类型 → 中文徽标文案
const TL_LABEL: Record<string, string> = {
  CALL: '通话', NOTE: '手记', FOLLOWUP: '跟进', FOLLOW_UP: '跟进', TICKET: '工单',
  SMS: '催费单', PAYLINK: '催费单', PROMISE: '承诺', STATUS: '状态变更',
  OPLOG: '操作日志', LEGAL: '法务', EVIDENCE: '存证', REPAY: '回款'
}
const tlLabel = (t?: string) => TL_LABEL[String(t ?? '').toUpperCase()] ?? (t ?? '')

// ===== 中栏三 Tab 切换（纯 UI）=====
const midTab = ref<'timeline' | 'project' | 'playbook'>('timeline')
// 时间线类型筛选（前端过滤，按 item.type 小写归类）
const tlFilter = ref<'all' | 'call' | 'note' | 'ticket' | 'promise' | 'legal' | 'sms' | 'status'>('all')
const TL_GROUP: Record<string, string> = {
  CALL: 'call', NOTE: 'note', FOLLOWUP: 'note', FOLLOW_UP: 'note', TICKET: 'ticket',
  SMS: 'sms', PAYLINK: 'sms', PROMISE: 'promise', REPAY: 'promise',
  STATUS: 'status', OPLOG: 'status', LEGAL: 'legal', EVIDENCE: 'legal'
}
const timeline = computed<any[]>(function () { return d.value?.timeline ?? [] })
const tlFiltered = computed<any[]>(function () {
  if (tlFilter.value === 'all') return timeline.value
  return timeline.value.filter(function (ev: any) { return TL_GROUP[String(ev.type ?? '').toUpperCase()] === tlFilter.value })
})
// 项目资料（同一 GET /cases/{id} 响应里的 projectRef）
const projectRef = computed<any>(function () { return d.value?.projectRef ?? {} })
// 减免决策 → 中文
const reduceDecideLabel: Record<string, string> = { AUTO: '自动', PROPERTY: '物业审批', PLATFORM: '平台审批', MANUAL: '人工审批' }

// ===== 录音就绪态（右栏 op-rec 纯展示，复用现有 latest/review 逻辑）=====
const recReady = computed<boolean>(function () { return !!(latest.value?.hasRecording && latest.value?.recording) })
const recObj = computed<any>(function () { return latest.value?.recording ?? null })

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
  // 作战手册静态底稿：优先 CaseDetail.playbook(同一响应)；否则容错取项目/批次 playbook 端点(其一)
  loadPlaybook()
}
// 作战手册静态文本：CaseDetail.playbook 优先，回退 /projects/{pid}/playbook 或 /batches/{bid}/playbook
async function loadPlaybook() {
  if (d.value?.playbook?.content) { playbookDoc.value = d.value.playbook; return }
  const pid = d.value?.case?.projectId
  const bid = d.value?.case?.batchId
  try {
    if (pid) {
      const { data } = await api.GET('/projects/{id}/playbook', { params: { path: { id: String(pid) } } } as any)
      if (data) { playbookDoc.value = data; return }
    }
    if (bid) {
      const { data } = await api.GET('/batches/{id}/playbook', { params: { path: { id: String(bid) } } } as any)
      if (data) { playbookDoc.value = data }
    }
  } catch { /* 容错：无手册端点则静默 */ }
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
  midTab.value = 'playbook'   // 复盘载入后切到作战手册 Tab（摘要/风险/建议在此渲染）
}
// 沟通记录点 call 项 → 拉取最新录音的 AI 复盘
async function openCallReview() {
  if (recObj.value) { loadReview(recObj.value.id); return }
  await getLatest()
  if (recObj.value) loadReview(recObj.value.id)
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
// 转工单类型受控选项(提交仍是 TicketInput.type 字符串)
const TICKET_TYPES: string[] = ['上门核实', '材料证明', '法务工单', '信息核实', '其他']
// 结案原因受控选项 — 优先取 settings(CFG-CLOSE-REASONS)，取不到回退预置；按 close kind 过滤
const CLOSE_REASONS_FALLBACK: Record<string, Array<{ code: string; label: string }>> = {
  WITHDRAWN: [
    { code: 'NEGOTIATED_WITHDRAW', label: '协商撤回' },
    { code: 'WRONG_FILING', label: '错误立案' },
    { code: 'OTHER', label: '其它' }
  ],
  BAD_DEBT: [
    { code: 'UNREACHABLE_NO_ASSET', label: '失联无财产' },
    { code: 'REFUSE_WRITEOFF', label: '拒缴核销' },
    { code: 'OTHER', label: '其它' }
  ]
}
// settings 下发的 close 原因(扁平 {kind,code,label})，loadCloseReasons 填充；空则用回退
const closeReasonsCfg = ref<Array<{ kind?: string; code?: string; label?: string }>>([])
// 当前结案类型对应的可选原因(settings 优先，按 kind 过滤；无则回退预置)
const closeReasonOptions = computed<Array<{ code: string; label: string }>>(function () {
  var kind = form.value && form.value.closeKind
  var fromCfg = closeReasonsCfg.value
    .filter(function (r: any) { return r && r.kind === kind && r.code })
    .map(function (r: any) { return { code: r.code, label: r.label || r.code } })
  return fromCfg.length ? fromCfg : (CLOSE_REASONS_FALLBACK[kind] || CLOSE_REASONS_FALLBACK.WITHDRAWN)
})
// 取 settings 的 CLOSE_REASONS 域(容错：无端点/无权限静默回退)
async function loadCloseReasons() {
  try {
    const { data } = await api.GET('/settings', { params: { query: { domain: 'CLOSE_REASONS' } } } as any)
    var arr = (data as any) || []
    var flat: Array<{ kind?: string; code?: string; label?: string }> = []
    arr.forEach(function (s: any) { (s && s.closeReasons ? s.closeReasons : []).forEach(function (r: any) { flat.push(r) }) })
    closeReasonsCfg.value = flat
  } catch { /* 容错：回退预置 */ }
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
    : kind === 'close' ? { closeKind: 'WITHDRAWN', reasonCode: '', reasonNote: '' }
    : kind === 'repay' ? { amountYuan: 0, channel: 'WECHAT_QR', paidAt: '', note: '' }
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
  else if (k === 'close') {
    // 受控原因下拉 → 取选中 label；选"其它"(code=OTHER)时拼接备注。最终 reason 仍是字符串(CloseInput.reason 必填)
    var picked = closeReasonOptions.value.find(function (o: any) { return o.code === f.reasonCode })
    var label = picked ? picked.label : ''
    var note = (f.reasonNote || '').trim()
    if (!label) { ElMessage.error('请选择结案原因'); return }
    var reason = (f.reasonCode === 'OTHER' && note) ? (label + '：' + note) : label
    res = await api.POST('/cases/{id}/close', { params: { path: { id } }, body: { kind: f.closeKind, reason } as any })
  }
  else if (k === 'repay') res = await api.POST('/cases/{id}/repay-lines', { params: { path: { id } }, body: { amountCents: Math.round(f.amountYuan * 100), channel: f.channel, paidAt: f.paidAt, note: (f.note && f.note.trim()) ? f.note.trim() : undefined } as any })
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
onMounted(function () { loadAll(); loadCloseReasons() })
</script>

<template>
  <div v-if="d" class="case3">
    <!-- ============ 左栏：业主画像 ============ -->
    <div class="col left">
      <div class="portrait-top">
        <div class="portrait-av" style="background:var(--primary)">{{ ownerInitial }}</div>
        <div class="portrait-id">
          <div class="nm">{{ d.case?.ownerName || '—' }}</div>
          <div class="sub">{{ d.case?.room || '—' }} · 户号 {{ d.case?.acctNo || '—' }}</div>
        </div>
        <div class="portrait-amt">
          <div class="a num">{{ yuan(d.case?.dueCents) }}</div>
          <div class="s">应收欠费</div>
        </div>
      </div>
      <!-- 状态徽标（省略原型画像风险标签 ptags：后端无数据） -->
      <div class="ptags" style="margin-top:12px">
        <span class="tag" :class="caseStatusTag(d.case?.status)" :title="d.case?.status">{{ caseStatusLabel(d.case?.status) }}</span>
        <span v-if="d.case?.pool" class="tag inf" :title="d.case.pool">{{ poolLabel(d.case.pool) }}</span>
        <span v-if="redacted" class="tag inf">已脱敏</span>
      </div>
      <div class="pstats" style="margin-top:12px">
        <div><div class="v num">{{ (d.contacts ?? []).length }}</div><div class="k">联系次数</div></div>
        <div><div class="v num">{{ promises.length }}</div><div class="k">承诺次数</div></div>
        <div><div class="v num">{{ tickets.length }}</div><div class="k">工单数</div></div>
      </div>

      <!-- 欠费详情：欠费周期=arrearagePeriods、应收=dueCents、减免后=reduceAfterCents -->
      <div class="sec-title">欠费详情</div>
      <table class="arrears"><tbody>
        <tr>
          <td>欠费周期</td>
          <td class="r" style="color:var(--reg);font-weight:400">
            <template v-if="(d.case?.arrearagePeriods ?? []).length">{{ d.case.arrearagePeriods.join('、') }}</template>
            <template v-else>—</template>
          </td>
        </tr>
        <tr><td>应收</td><td class="r">{{ yuan(d.case?.dueCents) }}</td></tr>
        <tr><td>减免后</td><td class="r" style="color:var(--success)">{{ yuan(d.case?.reduceAfterCents ?? d.case?.dueCents) }}</td></tr>
        <tr><td><b>状态</b></td><td class="r" style="font-weight:400"><span class="tag" :class="caseStatusTag(d.case?.status)" :title="d.case?.status">{{ caseStatusLabel(d.case?.status) }}</span></td></tr>
      </tbody></table>

      <!-- 联系方式（脱敏收敛态不渲染明细） -->
      <div class="sec-title" style="margin-top:14px">
        联系方式
        <el-button v-if="!summaryView && auth.has('case.follow')" size="small" text type="primary" @click="openAddContact">+ 新增</el-button>
      </div>
      <template v-if="summaryView">
        <div class="alert info" style="font-size:12px">本案已结案并脱敏（BR-M8-09）：明细已收敛，仅展示数量 {{ (d.contacts ?? []).length }} 个联系方式。</div>
      </template>
      <template v-else>
        <div v-for="ct in (d.contacts ?? [])" :key="ct.id" class="contact-item">
          <div class="ct-top">
            <span class="ct-phone">{{ ct.phone }}</span>
            <span class="ct-ops">
              <a v-if="!ct.isPrimary && !ct.invalid && auth.has('case.follow')" class="btn txt" style="font-size:12px" @click="setPrimaryContact(ct)">设为主号码</a>
              <a v-if="!ct.invalid && auth.has('case.follow')" class="btn txt" style="font-size:12px;color:var(--danger,#F56C6C)" @click="invalidContact(ct)">标记无效</a>
            </span>
          </div>
          <div class="ct-tags">
            <span class="tag inf">{{ ct.label }}</span>
            <span v-if="ct.isPrimary" class="tag pri">主号码</span>
            <span v-if="ct.invalid" class="tag dan">无效</span>
          </div>
        </div>
        <div v-if="!(d.contacts ?? []).length" class="note" style="font-size:12px">暂无联系方式。</div>
      </template>

      <!-- 最近承诺 -->
      <div class="sec-title" style="margin-top:14px">最近承诺</div>
      <template v-if="promises.length">
        <div style="font-size:13px;color:var(--reg);background:#fffbeb;border:1px solid #f5dab1;border-radius:6px;padding:9px;margin-top:4px">
          {{ promises[0].date }} {{ yuan(promises[0].amountCents) }}
          <span class="tag war" style="font-size:11px;margin-left:6px" :title="promises[0].state">{{ promises[0].state ? promiseStateLabel(promises[0].state) : '待兑现' }}</span>
          <span v-if="promises[0].installments?.length" class="tag inf" style="font-size:11px;margin-left:4px">{{ promises[0].installments.length }} 期</span>
        </div>
      </template>
      <div v-else class="note" style="font-size:12px">暂无承诺记录。</div>
    </div>

    <!-- ============ 中栏：三 Tab ============ -->
    <div class="col mid">
      <div class="dtabs" style="padding:14px 14px 0">
        <div class="t" :class="{ on: midTab === 'timeline' }" @click="midTab = 'timeline'">沟通记录</div>
        <div class="t" :class="{ on: midTab === 'project' }" @click="midTab = 'project'">项目资料</div>
        <div class="t" :class="{ on: midTab === 'playbook' }" @click="midTab = 'playbook'">作战手册</div>
      </div>

      <!-- Tab1：沟通记录（timeline） -->
      <div class="midpanel" v-show="midTab === 'timeline'">
        <div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px">
          <span class="tag-pick" :class="{ on: tlFilter === 'all' }" @click="tlFilter = 'all'">全部</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'call' }" @click="tlFilter = 'call'">通话</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'note' }" @click="tlFilter = 'note'">跟进</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'ticket' }" @click="tlFilter = 'ticket'">工单</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'promise' }" @click="tlFilter = 'promise'">承诺</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'legal' }" @click="tlFilter = 'legal'">法务存证</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'sms' }" @click="tlFilter = 'sms'">催费单</span>
          <span class="tag-pick" :class="{ on: tlFilter === 'status' }" @click="tlFilter = 'status'">状态日志</span>
        </div>
        <div class="tl" v-if="tlFiltered.length">
          <div
            class="e"
            :class="{ clickable: String(ev.type).toUpperCase() === 'CALL' }"
            v-for="ev in tlFiltered"
            :key="ev.id"
            @click="String(ev.type).toUpperCase() === 'CALL' ? openCallReview() : null"
          >
            <span class="ty" :class="tlTy(ev.type)">{{ tlLabel(ev.type) }}</span>
            <span class="tm">{{ ev.createdAt }}</span>
            {{ ev.content }}
            <span v-if="ev.actor" style="color:var(--sec);font-size:12px"> · {{ ev.actor }}</span>
            <span v-if="String(ev.type).toUpperCase() === 'CALL'" class="td-arr">›</span>
          </div>
        </div>
        <div v-else class="note">暂无记录。</div>
      </div>

      <!-- Tab2：项目资料（projectRef） -->
      <div class="midpanel" v-show="midTab === 'project'">
        <div class="sec-title" style="margin-top:0">项目档案</div>
        <div class="desc">
          <div class="r"><div class="k">项目名称</div><div class="v">{{ d.case?.projectName || '—' }}</div></div>
          <div class="r"><div class="k">合同类型</div><div class="v">{{ projectRef.contractType || '—' }}</div></div>
          <div class="r"><div class="k">批次号</div><div class="v">{{ d.case?.batchId || '—' }}</div></div>
        </div>
        <div class="sec-title">收费标准（收费依据）</div>
        <div class="desc">
          <div class="r"><div class="k">收费标准</div><div class="v">{{ projectRef.feeStd || '—' }}</div></div>
        </div>
        <div class="sec-title">收款信息（催收依据）</div>
        <div class="desc">
          <div class="r"><div class="k">收款信息</div><div class="v" style="white-space:pre-wrap">{{ projectRef.payInfo || '—' }}</div></div>
        </div>
        <div class="sec-title">减免规则</div>
        <template v-if="(projectRef.reduceTiers ?? []).length">
          <div class="desc">
            <div class="r" v-for="(t, ti) in projectRef.reduceTiers" :key="ti">
              <div class="k">档位 {{ ti + 1 }}</div>
              <div class="v">
                {{ t.discount }}
                <span v-if="t.waivePenalty" class="tag suc" style="margin-left:6px">免滞纳金</span>
                <span v-if="t.capCents != null" class="tag inf" style="margin-left:6px">上限 {{ yuan(t.capCents) }}</span>
                <span class="tag war" style="margin-left:6px">{{ reduceDecideLabel[t.decide] || t.decide }}</span>
              </div>
            </div>
          </div>
        </template>
        <div v-else class="note" style="font-size:12px">本项目暂无减免档位。</div>
      </div>

      <!-- Tab3：作战手册（AI 复盘摘要/风险/建议 + 静态 playbook） -->
      <div class="midpanel" v-show="midTab === 'playbook'">
        <template v-if="review">
          <div class="sec-title" style="margin-top:0">AI 通话复盘 <span style="font-size:12px;font-weight:400;color:var(--sec)">据本次通话生成，仅建议</span></div>
          <div class="bgbox">📋 通话小结：{{ review.summary || '—' }}</div>
          <!-- 风险条 -->
          <div
            v-for="(r, ri) in (review.risks ?? [])"
            :key="ri"
            class="riskbar"
            :class="(r.level === 'HIGH' || r.level === 'H') ? 'l2' : 'l1'"
          >
            ⚠ {{ r.level }} · {{ r.desc }}<span v-if="r.segmentTs" style="color:var(--sec)"> @{{ r.segmentTs }}</span>
          </div>
          <!-- 建议卡 StrategyCard -->
          <div class="aicard script" v-for="su in (review.suggestions ?? [])" :key="su.id">
            <div class="h">
              <span>💡 {{ su.type || '策略建议' }}</span>
              <span v-if="su.confidence" class="tag pri">置信度 {{ su.confidence }}</span>
            </div>
            <div class="ti">{{ su.title }}</div>
            <div class="tx">{{ su.body }}</div>
            <div v-if="su.trigger" class="note" style="font-size:11px;margin-top:4px">触发：{{ su.trigger }}</div>
            <div v-if="su.actionRef && su.actionRef !== 'NONE'" class="cta">
              <el-button size="small" type="primary" @click="adopt(su)">✓ 采纳 → {{ su.actionRef }}</el-button>
            </div>
          </div>
        </template>
        <div v-else class="alert info" style="margin-top:0">通话后在右侧操作区「查看 AI 复盘」或在沟通记录点通话项，载入本次通话的 AI 复盘（摘要/风险/建议）。</div>

        <!-- 静态：物业作战手册底稿（项目/批次维护，案件详情只读调阅） -->
        <div class="sec-title" style="margin-top:16px;padding-top:14px;border-top:1px solid var(--bd)">
          物业静态资料
          <span style="font-size:12px;font-weight:400;color:var(--sec)">随项目/批次维护，案件详情只读调阅</span>
        </div>
        <template v-if="playbookDoc?.content">
          <div class="note" style="line-height:2;background:#f8fafc;border:1px solid var(--bd);border-radius:6px;padding:10px;white-space:pre-wrap">
            <b v-if="playbookDoc.version">版本：{{ playbookDoc.version }}</b><br v-if="playbookDoc.version">
            {{ playbookDoc.content }}
          </div>
        </template>
        <div v-else class="note" style="font-size:12px">暂无作战手册底稿（项目/批次未维护）。</div>
      </div>
    </div>

    <!-- ============ 右栏：操作区 ============ -->
    <div class="col right">
      <div class="opzone">
        <div style="font-size:13px;font-weight:600;color:var(--txt);margin-bottom:10px;display:flex;align-items:center;gap:6px">
          <span style="width:3px;height:13px;background:var(--primary);border-radius:2px;display:inline-block"></span>操作区
        </div>

        <!-- 录音解析（复用现有 latest/review 逻辑） -->
        <div v-if="auth.has('case.call')" class="op-rec">
          <div class="op-rec-h">本次通话回填</div>
          <div class="note" style="font-size:11px;margin:0 0 8px;line-height:1.6">ⓘ 平台不感知拨打时机；按作战手册通话后，点下方拉取本机最新录音（App 自动上传解析）。</div>
          <button class="btn sm" style="width:100%" @click="getLatest">🔄 获取最新通话录音</button>
          <div v-if="recReady" class="rec-ready">
            <div class="rr-meta">
              <span class="tag" :class="recStatusTag(recObj.status)" :title="recObj.status">{{ callRecStatusLabel(recObj.status) }}</span>
              最新通话 · {{ recObj.durationSec }}s
            </div>
            <button v-if="recObj.status === 'READY'" class="btn sm" style="width:100%;margin-top:7px" @click="loadReview(recObj.id)">查看并标注（AI 复盘）→</button>
            <div class="toolbar" style="margin-top:7px;gap:6px;flex-wrap:wrap">
              <el-button size="small" @click="router.push(`/cases/${id}/call/${recObj.id}`)">通话详情</el-button>
              <el-button v-if="recObj.status === 'FAILED'" size="small" @click="reprocessRec(recObj.id)">重新处理</el-button>
              <el-button v-if="recObj.status === 'QUOTA_BLOCKED'" size="small" type="warning" @click="parseRec(recObj.id)">补解析</el-button>
              <el-button v-if="recObj.status === 'QUOTA_BLOCKED'" size="small" @click="batchParseRec">批量补解析</el-button>
              <el-button v-if="auth.has('case.follow')" size="small" @click="openMark(recObj.id)">标记结果</el-button>
            </div>
            <div v-if="recObj.status === 'QUOTA_BLOCKED'" class="alert warn" style="font-size:11px;margin-top:6px">解析余额不足已暂停（BR-M5-02）：充值后点「补解析」续解析。</div>
          </div>
          <div class="note" style="font-size:11px;margin-top:8px;line-height:1.7">
            未发现新录音？App 可能仍在上传 ·
            <label class="btn txt" style="padding:0 2px;cursor:pointer">手动上传<input type="file" hidden accept="audio/*" @change="uploadRecording" /></label>（救济）
          </div>
          <div style="border-bottom:1px solid var(--bd);margin:12px 0"></div>
        </div>

        <!-- 操作区按钮（复用现有 openAct(kind)；显隐沿用 canAct 判断） -->
        <div class="sec-title" style="margin:6px 0 6px">催收作业</div>
        <button v-if="canAct('follow', 'case.follow')" class="btn sm" style="width:100%;margin-bottom:6px" @click="openAct('follow', '写跟进')">写跟进</button>
        <button v-if="canAct('promise', 'case.promise')" class="btn sm" style="width:100%;margin-bottom:6px" @click="openAct('promise', '登记承诺')">登记承诺</button>
        <button v-if="canAct('ticket', 'case.ticket')" class="btn sm" style="width:100%;margin-bottom:6px" @click="openAct('ticket', '转工单')">转工单</button>
        <button v-if="canAct('paylink', 'case.paylink')" class="btn sm" style="width:100%;margin-bottom:6px" @click="openAct('paylink', '发缴费链接')">发缴费链接</button>
        <button v-if="canAct('repay', 'case.repay.mark')" class="btn sm" style="width:100%;margin-bottom:6px" @click="openAct('repay', '登记还款')">登记还款</button>
        <button v-if="canAct('follow', 'case.follow')" class="btn sm" style="width:100%;margin-bottom:6px" @click="suggestLegal">建议走法务</button>
        <button v-if="canAct('release', 'case.release')" class="btn sm" style="width:100%;margin-bottom:6px" @click="lifecycle('释放', '/cases/{id}/release')">释放</button>
        <button v-if="canAct('return', 'case.return')" class="btn sm" style="width:100%;margin-bottom:6px" @click="lifecycle('退回', '/cases/{id}/return')">退回</button>

        <!-- 缴费链接（本会话创建·重发/作废 BR-M4-14） -->
        <template v-if="payLinks.length">
          <div class="sec-title" style="margin:10px 0 6px">缴费链接（本会话）</div>
          <div v-for="l in payLinks" :key="l.id" class="note" style="font-size:12px;border:1px solid var(--bd);border-radius:6px;padding:7px 9px;margin-bottom:6px">
            <div>{{ l.token }} · {{ yuan(l.amountCents) }} <span class="tag" :class="l.status === 'ACTIVE' ? 'suc' : 'inf'">{{ l.status === 'ACTIVE' ? '有效' : '已失效' }}</span></div>
            <div v-if="l.status === 'ACTIVE' && auth.has('case.paylink')" class="toolbar" style="margin-top:5px;gap:6px">
              <el-button size="small" @click="resendLink(l)">重发</el-button>
              <el-button size="small" text type="danger" @click="voidLink(l)">作废</el-button>
            </div>
          </div>
        </template>

        <!-- 送达存证（PC/PL/SA：律师函/催收单/诉讼/证据下载/存证清单·复用现有 evidence/legal 入口） -->
        <template v-if="canAct('legal', 'legal.create') || canAct('evidence', 'evidence.create')">
          <div class="sec-title" style="margin-top:14px">送达存证</div>
          <button v-if="canAct('legal', 'legal.create')" class="btn df sm" style="width:100%;margin-bottom:6px" @click="openAct('legal', '申请法务文书')">⚖ 申请法务文书</button>
          <button v-if="canAct('evidence', 'evidence.create')" class="btn df sm" style="width:100%;margin-bottom:6px" @click="openAct('evidence', '发起存证')">🔒 发起存证（录音/送达/材料包）</button>
          <!-- 法务文书列表：登记送达入口 -->
          <template v-if="legalDocs.length">
            <div v-for="doc in legalDocs" :key="doc.id" class="note" style="font-size:12px;border:1px solid var(--bd);border-radius:6px;padding:7px 9px;margin-bottom:6px">
              <div><span :title="doc.type">{{ legalDocTypeLabel(doc.type) }}</span> <span class="tag inf" :title="doc.status">{{ legalDocStatusLabel(doc.status) }}</span></div>
              <div v-if="doc.status !== 'DELIVERED' && auth.has('legal.create')" class="toolbar" style="margin-top:5px">
                <el-button size="small" @click="deliverLegal(doc)">登记送达</el-button>
              </div>
            </div>
          </template>
        </template>

        <!-- CO 法务进度只读（case.legalStage） -->
        <template v-if="d.case?.legalStage">
          <div class="sec-title" style="margin-top:14px">法务进度</div>
          <div class="alert info" style="margin-top:0;font-size:12px">当前法务阶段：<b :title="d.case.legalStage">{{ legalStageLabel(d.case.legalStage) }}</b>（只读，由协调员主导）。</div>
        </template>

        <!-- 危险操作（作废/坏账 → 结案 dlg） -->
        <template v-if="canAct('close', 'case.close')">
          <div class="sec-title" style="color:var(--dg,#F56C6C);margin-top:14px">终态操作（不可撤销）</div>
          <button class="btn sm dg" style="width:100%" @click="openAct('close', '结案')">结案 / 坏账</button>
        </template>

        <div v-if="!canAct('follow', 'case.follow') && !canAct('legal', 'legal.create') && !canAct('close', 'case.close')" class="note" style="margin:8px 0 0">当前角色暂无可操作权限。</div>
      </div>
    </div>

    <!-- ===================== 保留 EL 对话框（仅触发入口换到三栏） ===================== -->
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
          <el-form-item label="类型"><el-select v-model="form.type" style="width:100%"><el-option v-for="t in TICKET_TYPES" :key="t" :label="t" :value="t" /></el-select></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='repay'">
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option v-for="c in ['WECHAT_QR','BANK_TRANSFER','CASH']" :key="c" :label="c" :value="c" /></el-select></el-form-item>
          <el-form-item label="到账日"><el-date-picker v-model="form.paidAt" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="2" placeholder="凭证说明 / 备注(可选)" /></el-form-item>
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
          <el-form-item label="结案类型"><el-select v-model="form.closeKind" @change="form.reasonCode=''"><el-option label="撤案" value="WITHDRAWN" /><el-option label="坏账" value="BAD_DEBT" /></el-select></el-form-item>
          <el-form-item label="原因"><el-select v-model="form.reasonCode" style="width:100%" placeholder="请选择结案原因"><el-option v-for="o in closeReasonOptions" :key="o.code" :label="o.label" :value="o.code" /></el-select></el-form-item>
          <el-form-item v-if="form.reasonCode==='OTHER'" label="备注"><el-input v-model="form.reasonNote" type="textarea" :rows="2" placeholder="其它原因说明" /></el-form-item>
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
