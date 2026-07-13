<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { caseStatusLabel, poolLabel, promiseStateLabel, callRecStatusLabel, legalDocTypeLabel, legalDocStatusLabel, legalStageLabel } from '../constants/enums'
import { roleName } from '../constants/roles'
import DsDrawer from './DsDrawer.vue'
import PayLinkCard from './PayLinkCard.vue'
import AiReviewPanel from './AiReviewPanel.vue'
import RecordingAudioPlayer from './RecordingAudioPlayer.vue'
import AttachmentUpload from './AttachmentUpload.vue'
import { downloadDoc } from '../utils/wordExport'

// M4 催收三栏接打台：左画像 / 中三Tab(沟通记录·项目资料·作战手册) / 右操作区。动作按 /me 权限门控。
// 可复用组件：既承载 /cases/:id 整页路由（见 CaseDetailView.vue 薄壳），也内嵌进催收员工作台
// 「今日必办」选中预览（DashboardView.vue cockpit master-detail，对标原型 <case-three-col> 复用）。
const props = defineProps<{ caseId: string }>()
const emit = defineEmits<{ loaded: [detail: any] }>()
const router = useRouter(); const auth = useAuth()
let id = props.caseId
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
const markCodes = computed<Array<{ code: string; label: string; connected?: boolean }>>(function () {
  var src = d.value && d.value.markCodes ? d.value.markCodes : []
  var enabled = src.filter(function (m: any) { return m && m.enabled !== false && m.code })
    .map(function (m: any) { return { code: m.code, label: m.label || m.code, connected: m.connected !== false } })
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
const paylinkResult = ref<any>(null)   // 发催费单成功后：弹窗内直接展示业主 H5 链接+二维码+短信发送，不急着关窗
const paylinkLoading = ref(false)   // 打开「发催费单」到自动生成完成之间的过渡态
const dlgError = ref('')   // 提交失败原因常驻展示在弹窗内（如短信冷却），避免只闪一下 toast 被错过
const latest = ref<any>(null)
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
// 画像底色：按结案/坏账变灰，否则按风险色（演示用固定色）
const avatarBg = computed<string>(function () {
  var s = d.value?.case?.status
  if (s === 'SETTLED' || s === 'WITHDRAWN' || s === 'BAD_DEBT' || s === 'VOIDED') return 'var(--sec)'
  return 'var(--primary)'
})
// 风险标签（演示，后端有数据后改用 review.risks）
const riskTags = computed<string[]>(function () {
  var review = d.value?.aiReview || d.value?.review
  if (!review?.risks?.length) return []
  return review.risks.map(function (r: any) { return r.type || r.label || '' }).filter(Boolean)
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

// ===== 中栏三 Tab 切换（纯 UI）。非催收员默认作战手册，催收员默认沟通记录。=====
const isCollector = computed<boolean>(function () { return auth.me?.role === 'CO' })
const role = computed<string>(function () { return auth.me?.role ?? '' })
// PC 协调员（中栏「待办事项」协作 Tab 门控）
const isCoordinator = computed<boolean>(function () { return auth.me?.role === 'PC' })
// 协作 Tab 徽标：CO 看已处理数（有回执可看）、PC 看待处理数（催办提醒）
const collabDoneCount = computed<number>(function () { return tickets.value.filter(function (t: any) { return t.status === 'HANDLED' }).length })
const collabPendingCount = computed<number>(function () { return tickets.value.filter(function (t: any) { return t.status !== 'HANDLED' }).length })
// 送达存证区域门控（对标原型 ['PC','PL','SA','SE'].includes(role)）：物业 + 平台角色，服务商/催收员不展示。
const isPropertyRole = computed<boolean>(function () { return ['PL', 'PC', 'SA', 'SE'].includes(role.value) })
// SA/SE 平台角色
const isPlatformRole = computed<boolean>(function () { return ['SA', 'SE'].includes(role.value) })
// VL/CO 服务商角色
const isProviderRole = computed<boolean>(function () { return ['VL', 'CO'].includes(role.value) })
const midTab = ref<'timeline' | 'project' | 'playbook' | 'collab'>('playbook')
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
// 沟通记录逐条详情抽屉（对标原型 openDrawer：CALL 走 AI 复盘，其余弹右侧详情抽屉）
const tldlg = ref(false)
const tlDetail = ref<any>(null)
function openTlDetail(ev: any) {
  if (String(ev.type ?? '').toUpperCase() === 'CALL') { openCallReview(); return }
  tlDetail.value = ev
  tldlg.value = true
}
// 详情抽屉的关联实体：按 activity.refType/refId 从已加载列表回查（工单/承诺/回款/法务文书），查不到只展示事件本身
const tlLinked = computed<{ kind: string; data: any } | null>(function () {
  const ev = tlDetail.value
  if (!ev?.refType || ev.refId == null) return null
  const rid = String(ev.refId)
  if (ev.refType === 'ticket') return { kind: 'ticket', data: tickets.value.find(function (t: any) { return String(t.id) === rid }) }
  if (ev.refType === 'promise') return { kind: 'promise', data: promises.value.find(function (p: any) { return String(p.id) === rid }) }
  if (ev.refType === 'repay_line') return { kind: 'repay', data: repays.value.find(function (r: any) { return String(r.id) === rid }) }
  if (ev.refType === 'legal_doc') return { kind: 'legal', data: legalDocs.value.find(function (l: any) { return String(l.id) === rid }) }
  if (ev.refType === 'pay_link') return { kind: 'paylink', data: payLinks.value.find(function (l: any) { return String(l.id) === rid }) }
  return null
})
// 项目资料（同一 GET /cases/{id} 响应里的 projectRef）
const projectRef = computed<any>(function () { return d.value?.projectRef ?? {} })
// 物业侧(PL/PC)：批次信息看付佣比例(=commInRate 物业视角)；服务商负责人(VL)看收佣比例(=payOutRate)；平台(SA/SE)双线+毛利。
const isPropertyOrg = computed<boolean>(function () { return role.value === 'PL' || role.value === 'PC' })
// 平台毛利 = 收佣% − 付佣%（commInRate/payOutRate 为 "12%" 形式的展示串，parseFloat 取数值）
const grossMargin = computed<string | null>(function () {
  var ci = parseFloat(projectRef.value?.commInRate), po = parseFloat(projectRef.value?.payOutRate)
  if (isNaN(ci) || isNaN(po)) return null
  return (Math.round((ci - po) * 100) / 100) + '%'
})
// 通话前策略（契约 CaseDetail.preCallStrategy · BR-M5-04）：points[0]=背景摘要(bgbox)、其余=警示条(riskbar)、
// objections=可采纳的 StrategyCard(aicard)。忽略仅本地隐藏（不落库）。
const preCall = computed<any>(function () { return d.value?.preCallStrategy ?? null })
const preCallBg = computed<string>(function () { return preCall.value?.points?.[0] ?? '' })
const preCallWarns = computed<string[]>(function () { return (preCall.value?.points ?? []).slice(1) })
const dismissedCards = ref<Record<string, boolean>>({})
const preCallCards = computed<any[]>(function () {
  return (preCall.value?.objections ?? []).filter(function (c: any) { return !dismissedCards.value[String(c.id)] })
})
function dismissCard(c: any) { dismissedCards.value[String(c.id)] = true; ElMessage.info('已忽略该建议') }
// 置信度标签 → 中文
const confLabel = (c?: string) => (c === 'HIGH' ? '高' : c === 'MED' ? '中' : c === 'LOW' ? '低' : c ?? '')
// 减免决策 → 中文（ReduceDecide: COLLECTOR_SELF=催收员自决 OFFLINE_INTERNAL=线下内部流程 PL_APPROVE=物业负责人核准）
const reduceDecideLabel: Record<string, string> = { COLLECTOR_SELF: '催收员自决', OFFLINE_INTERNAL: '线下内部流程', PL_APPROVE: '物业负责人核准' }

// ===== 录音就绪态（右栏 op-rec 纯展示，复用现有 latest/review 逻辑）=====
const recReady = computed<boolean>(function () { return !!(latest.value?.hasRecording && latest.value?.recording) })
const recObj = computed<any>(function () { return latest.value?.recording ?? null })

async function loadAll() {
  const det = await api.GET('/cases/{id}', { params: { path: { id } } })
  if (det.error) { ElMessage.error('加载失败'); return }
  d.value = det.data
  emit('loaded', d.value)   // 供内嵌场景（工作台今日必办预览）的外层展示头部信息，无需重复拉取
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
// 回听录音音频改由 <RecordingAudioPlayer> 组件承载（recordingId 变化即自动清理，无需本组件维护音频态）
// AI 复盘：统一走右侧复盘面板（AiReviewPanel，对标原型 .reviewpanel——左对话记录/右复盘/底部存证+保存标注）
const reviewOpen = ref(false)
const reviewRecId = ref('')
function loadReview(recId: string) {
  reviewRecId.value = String(recId)
  reviewOpen.value = true
}
// 沟通记录点 call 项 → 打开最新录音的 AI 复盘面板
async function openCallReview() {
  if (recObj.value) { loadReview(recObj.value.id); return }
  await getLatest()
  if (recObj.value) loadReview(recObj.value.id)
}
// 面板建议卡「采纳」→ 联动动作弹窗（复用 adopt 权限校验 + sourceSuggestionId 溯源）
function onReviewAdopt(card: any) {
  reviewOpen.value = false
  adopt(card)
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
// GET /settings 为平台数据域(x-data-scope=platform)，仅 SA/SE 可读；物业/服务商角色直接用预置回退，
// 避免必然的 403 噪声（结案原因预置项即规范列表，对标原型静态原因）。
async function loadCloseReasons() {
  if (!isPlatformRole.value) return
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
function openAct(kind: string, title: string, sourceSuggestionId?: string) {
  form.value = kind === 'follow' ? { content: '', method: 'CALL', attachments: [], sourceSuggestionId }
    : kind === 'promise' ? { date: '', amountYuan: 0, installments: [], sourceSuggestionId }
    : kind === 'ticket' ? { type: '上门核实', note: '', sourceSuggestionId }
    : kind === 'close' ? { closeKind: 'WITHDRAWN', reasonCode: '', reasonNote: '' }
    // idemKey 在**弹窗打开时**生成一次，提交时原样带上。
    // 关键在「一次」：双击提交、或提交超时后重试，用的都是同一个键 →
    // 后端按 repay_line.idem_key 的唯一索引把第二次挡下来，并把**第一次那笔**原样返回，
    // 而不是再造出一笔 5000 元（案件会被误判结清、物业多收一笔佣金、服务商多付一笔、催收员多拿一笔提成）。
    // 反例：在提交函数里现调 crypto.randomUUID() —— 每次点击都是新键，等于没有幂等。
    // （本仓 SmsOrgDetail / QuotaOrgDetail 目前正是这么写的，见 PR 说明。）
    : kind === 'repay' ? { amountYuan: 0, channel: 'WECHAT_QR', paidAt: '', note: '', idemKey: crypto.randomUUID() }
    : kind === 'legal' ? { type: 'COLLECTION_LETTER' }
    : kind === 'reduce' ? { type: '减免滞纳金', amountYuan: 0, reason: '' }
    : {}
  paylinkResult.value = null
  dlgError.value = ''
  dlg.value = { open: true, kind, title }
}
// 发催费单：打开即自动生成免费预览链接（WECHAT_COPY，不扣条数），操作员看完账单内容再决定复制/下载二维码/短信发送，
// 短信不再是创建时的前置二选一，而是预览后的一个独立动作（同一 token 指定渠道重发，见 sendSmsForPreview）。
async function openPaylink(title: string, sourceSuggestionId?: string) {
  form.value = {}
  paylinkResult.value = null
  dlgError.value = ''
  dlg.value = { open: true, kind: 'paylink', title }
  paylinkLoading.value = true
  const res = await api.POST('/cases/{id}/pay-links', { params: { path: { id } }, body: { channel: 'WECHAT_COPY', sourceSuggestionId } as any })
  paylinkLoading.value = false
  if (res.error) {
    const msg = (res.error as any)?.message ?? '未知错误'
    dlgError.value = msg
    ElMessage.error('生成失败：' + msg)
    return
  }
  payLinks.value.unshift(res.data)   // 捕获新链接→"本会话"列表可重发/作废
  paylinkResult.value = res.data
  loadAll()
}
// 短信发送（预览后的独立动作）：同一 token 指定 channel=SMS 重发，受短信冷却约束；账单内容不随渠道变化，无需刷新预览。
async function sendSmsForPreview() {
  if (!paylinkResult.value) return
  try {
    await ElMessageBox.confirm('短信发送将扣除 1 条短信，确认发送？', '短信发送', { confirmButtonText: '确认发送', cancelButtonText: '取消' })
  } catch { return }
  const { error } = await api.POST('/pay-links/{id}/resend', { params: { path: { id: String(paylinkResult.value.id) } }, body: { channel: 'SMS' } as any })
  if (error) { ElMessage.error('短信发送失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已通过短信发送'); loadAll()
}
// follow 跟进附件增删(name+url)
function addAttachment() { (form.value.attachments ||= []).push({ name: '', url: '' }) }
function removeAttachment(i: number) { form.value.attachments.splice(i, 1) }
// 上传件(直传/扫码)回填进 attachments(去重 by url)，随跟进一并提交
function onFollowUploaded(items: Array<{ id: string; name: string; url: string }>) {
  const arr = (form.value.attachments ||= [])
  const seen = new Set(arr.map((a: any) => a.url))
  items.forEach((it) => { if (!seen.has(it.url)) { arr.push({ name: it.name, url: it.url }); seen.add(it.url) } })
}
function addInstallment() { (form.value.installments ||= []).push({ seq: form.value.installments.length + 1, dueDate: '', amountYuan: 0 }) }
function removeInstallment(i: number) {
  const arr = form.value.installments
  if (!arr || arr.length <= 1) { arr && (form.value.installments = []); return }
  arr.splice(i, 1)
  arr.forEach((x: any, k: number) => { x.seq = k + 1 })
}
// 减免申请（BR-M2-18a）：本项目减免阶梯 = projectRef.reduceTiers（同一响应已带，按 id 序，0-based 对齐后端 tierIndex）。
// 按金额匹配最窄命中档（cap ≥ 金额）判定决定权；无政策回落 ≤¥500 自决、否则线下——对标原型 reduceDecide。
const reduceTiers = computed<any[]>(function () { return projectRef.value?.reduceTiers ?? [] })
type TierMatch = { index: number; decide: string; cap: number }
const reduceTierMatch = computed<{ index: number; decide: string }>(function () {
  const amt = Number(form.value?.amountYuan) || 0
  const tiers = reduceTiers.value
  if (!tiers.length) return { index: -1, decide: amt <= 500 ? 'COLLECTOR_SELF' : 'OFFLINE_INTERNAL' }
  const best = tiers.reduce(function (acc: TierMatch | null, t: any, i: number) {
    const cap = t.capCents == null ? Infinity : t.capCents
    if (amt * 100 <= cap && (acc === null || cap < acc.cap)) return { index: i, decide: t.decide, cap }
    return acc
  }, null as TierMatch | null)
  return best ? { index: best.index, decide: best.decide } : { index: -1, decide: 'OFFLINE_INTERNAL' }
})
const reduceSelfService = computed<boolean>(function () { return reduceTierMatch.value.decide === 'COLLECTOR_SELF' })
async function submitAct() {
  const k = dlg.value.kind, f = form.value
  dlgError.value = ''
  let res: any
  if (k === 'follow') {
    // M-08: 跟进携带附件(过滤掉空行)
    const atts = (f.attachments || []).filter((a: any) => a && (a.name || a.url))
    res = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: f.content, method: f.method, attachments: atts.length ? atts : undefined, sourceSuggestionId: f.sourceSuggestionId } as any })
  }
  else if (k === 'promise') {
    // 分期模式：总到期日/总金额从各期推导（最后一期到期日 / 各期金额之和），单笔模式沿用顶层 date/amountYuan。
    const inst = (f.installments || []).map((x: any) => ({ seq: x.seq, dueDate: x.dueDate, amountCents: Math.round((x.amountYuan || 0) * 100) }))
    const date = inst.length ? inst[inst.length - 1].dueDate : f.date
    const amountCents = inst.length ? inst.reduce((s: number, x: any) => s + x.amountCents, 0) : Math.round((f.amountYuan || 0) * 100)
    res = await api.POST('/cases/{id}/promises', { params: { path: { id } }, body: { date, amountCents, installments: inst.length ? inst : undefined, sourceSuggestionId: f.sourceSuggestionId } as any })
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
  else if (k === 'repay') res = await api.POST('/cases/{id}/repay-lines', {
    // Idempotency-Key 用弹窗打开时生成的那一个（见 openAct）——双击/重试都是同一个键。
    params: { path: { id }, header: { 'Idempotency-Key': f.idemKey } },
    body: { amountCents: Math.round(f.amountYuan * 100), channel: f.channel, paidAt: f.paidAt, note: (f.note && f.note.trim()) ? f.note.trim() : undefined },
  } as any)
  else if (k === 'legal') res = await api.POST('/cases/{id}/legal-docs', { params: { path: { id } }, body: { type: f.type } as any })
  else if (k === 'reduce') {
    if (!(f.reason && f.reason.trim())) { ElMessage.error('请填写减免原因'); return }
    const m = reduceTierMatch.value
    res = await api.POST('/cases/{id}/reductions', { params: { path: { id } }, body: { tierIndex: m.index >= 0 ? m.index : 0, amountCents: Math.round((f.amountYuan || 0) * 100), note: f.reason.trim() } as any })
    if (!res.error) {
      const d2 = res.data as any
      ElMessage.success(d2?.state === 'EFFECTIVE' ? '减免已生效（催收员自决）' : '已提交线下流程（留痕，由物业内部处理）')
      dlg.value.open = false; loadAll(); return
    }
  }
  else return   // 发催费单不再走这条通用提交管线，见 openPaylink/sendSmsForPreview
  if (res.error) {
    const msg = (res.error as any)?.message ?? '未知错误'
    dlgError.value = msg
    ElMessage.error('提交失败：' + msg)
    return
  }
  ElMessage.success(dlg.value.title + '成功'); dlg.value.open = false; loadAll()
}
// 缴费链接 重发/作废（BR-M4-14；link id 来自创建响应，契约无 per-case 列表端点；链接文本/二维码由 PayLinkCard 承载）
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
// ── 操作区（对标原型 OPS + groupedCaseOps + dangerOps）──
const OPS = [
  { p: 'case.follow', label: '写跟进记录', act: 'follow', cls: '', group: 'follow' },
  { p: 'case.promise', label: '登记承诺', act: 'promise', cls: 'df', group: 'follow' },
  { p: 'case.ticket', label: '转工单', act: 'ticket', cls: 'df', group: 'follow' },
  { p: 'case.paylink', label: '发催费单', act: 'paylink', cls: 'df', group: 'follow' },
  { p: 'case.repay.mark', label: '标线下回款', act: 'repay', cls: 'df', group: 'follow' },
  // 「建议法务」是催收员轻标（不改状态、不移案 · 对标原型 case.suggestLegal，仅 CO）；物业侧直接走「申请律师函」，故 CO 专属。
  { p: 'case.follow', label: '建议法务', act: 'suggestLegal', cls: 'df', group: 'follow', onlyCO: true },
  { p: 'case.reduce', label: '减免审批', act: 'reduce', cls: 'df', group: 'manage' },
  { p: 'case.release', label: '释放', act: 'release', cls: 'df', group: 'manage' },
  { p: 'case.return', label: '退回', act: 'return', cls: 'df', group: 'manage' },
  { p: 'case.close', label: '撤案/坏账', act: 'close', cls: 'dg', group: 'danger' },
]
const OPGROUPS: [string, string][] = [['follow', '跟进动作'], ['manage', '管理']]

// 根据权限过滤可见操作（onlyCO 项仅催收员可见，对标原型角色门控）
const caseOps = computed(() => OPS.filter(o => auth.has(o.p) && (!(o as any).onlyCO || isCollector.value)))
const groupedCaseOps = computed(() =>
  OPGROUPS.map(([k, label]) => ({ key: k, label, items: caseOps.value.filter(o => o.group === k) })).filter(g => g.items.length)
)
const dangerOps = computed(() => caseOps.value.filter(o => o.group === 'danger'))

// 操作分发
function onOp(o: typeof OPS[number]) {
  const act = o.act
  if (act === 'follow') openAct('follow', '写跟进记录')
  else if (act === 'promise') openAct('promise', '登记承诺')
  else if (act === 'ticket') openAct('ticket', '转工单')
  else if (act === 'paylink') openPaylink('发催费单')
  else if (act === 'repay') openAct('repay', '标线下回款')
  else if (act === 'suggestLegal') suggestLegal()
  else if (act === 'reduce') openAct('reduce', '减免审批')
  else if (act === 'release') lifecycle('释放', '/cases/{id}/release')
  else if (act === 'return') lifecycle('退回', '/cases/{id}/return')
  else if (act === 'close') openAct('close', '结案')
}

// ── 送达存证（PL/PC 专属功能，对标原型 §送达存证）──
// 案件当事人/欠费要素（诉状 + 催收单共用；缺失项在诉状预览标红，引导到项目管理补全）
const primaryContact = computed<any>(function () {
  var cs = d.value?.contacts ?? []
  return cs.find(function (x: any) { return x.isPrimary }) ?? cs[0] ?? null
})
const suitData = computed(function () {
  var c: any = d.value?.case ?? {}
  var pr: any = projectRef.value ?? {}
  var lit: any = c.litigationFields ?? {}
  var ap: string[] = c.arrearagePeriods ?? []
  return {
    plaintiff: auth.me?.org?.name ?? '',           // 原告=本物业组织名
    creditCode: '', plaintiffAddr: '', legalRep: '', plaintiffPhone: '',   // 组织级要素（当前数据未采集→缺失）
    defendant: c.ownerName ?? '',
    defendantId: lit.idCard ?? '',
    estate: (c.projectName ?? '') + (c.room ? ' ' + c.room : ''),
    defendantPhone: primaryContact.value?.phone ?? '',
    arrears: c.dueCents != null ? yuan(c.dueCents) : '',
    arrearsPeriod: ap.length ? (ap[0] + ' ~ ' + ap[ap.length - 1]) : '',
    penalty: c.penaltyCents != null ? yuan(c.penaltyCents) : '',
    contractType: pr.contractType || pr.contractName || '',
    feeStd: pr.feeStd ?? '', feeCycle: pr.feeCycle ?? '',
    projectName: c.projectName ?? '',
  }
})
const suitMissing = computed<string[]>(function () {
  var s: any = suitData.value
  var checks: Array<[string, any]> = [
    ['信用代码', s.creditCode], ['物业地址', s.plaintiffAddr], ['法定代表人', s.legalRep],
    ['被告姓名', s.defendant], ['身份证号', s.defendantId], ['房屋坐落', s.estate],
    ['欠费金额', s.arrears], ['欠费周期', s.arrearsPeriod], ['合同类型', s.contractType], ['收费标准', s.feeStd],
  ]
  return checks.filter(function (x) { return !x[1] }).map(function (x) { return x[0] })
})
const suitDlg = ref(false)
function openSuitPreview() { suitDlg.value = true }

// ── 文书导出 Word(.doc)：读弹窗内已渲染正文 innerHTML(内联样式随出)，平台统一模板 + 物业信息已自动填充 ──
function docTag() { var c: any = d.value?.case ?? {}; return (c.ownerName ?? '业主') + (c.room ? '_' + c.room : '') }
function downloadSuitDoc() {
  var el = document.getElementById('suit-print'); if (!el) return
  downloadDoc('民事起诉状_' + docTag() + '.doc', el.innerHTML, '民事起诉状')
  ElMessage.success('已下载民事起诉状 Word（平台出具）'); suitDlg.value = false
}
function downloadCollectionDoc() {
  var el = document.getElementById('collection-print')
  if (!el) { ElMessage.warning('请先打开催收单预览'); return }
  downloadDoc('催收通知单_' + docTag() + '.doc', el.innerHTML, '物业费催收通知单')
  ElMessage.success('已下载催收通知单 Word')
}
// ── 上传文件/凭证（送达凭证/沟通材料）：上传即记跟进；协调员可勾选同时上链存证(DELIVERY) ──
// 合并原「上传送达存证」「发起存证(录音/材料包)」为一个入口——上传是跟进/协调方式之一，是否存证由协调员决定。
const uploadDlg = ref(false)
const uploadItems = ref<Array<{ id: string; name: string; url: string }>>([])
const uploadNote = ref('')
const uploadEvidence = ref(false)   // 是否同时上链存证，默认不勾
// 送达类型（默认「其他」）：经本弹窗上传即记为送达凭证(带类型)，进协调员「送达管理」列表；随附件写 delivery_type。
const uploadDeliveryType = ref('OTHER')
const DELIVERY_TYPES = [
  { v: 'LAWYER_LETTER', label: '律师函' }, { v: 'COLLECTION_NOTICE', label: '催收单' },
  { v: 'COURT_DOC', label: '诉讼文书' }, { v: 'OTHER', label: '其他' },
]
function openUpload() { uploadItems.value = []; uploadNote.value = ''; uploadEvidence.value = false; uploadDeliveryType.value = 'OTHER'; uploadDlg.value = true }
function onUploaded(items: Array<{ id: string; name: string; url: string }>) {
  const seen = new Set(uploadItems.value.map((i) => i.id))
  items.forEach((it) => { if (!seen.has(it.id)) uploadItems.value.push(it) })
}
async function submitUpload() {
  if (!uploadItems.value.length) { ElMessage.warning('请先上传至少一个文件'); return }
  const names = uploadItems.value.map((i) => i.name).join('、')
  // a. 记跟进（时间线）：content=备注或“上传文件：xxx”，携带附件
  const { error: fErr } = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: uploadNote.value || ('上传文件：' + names), method: 'OTHER', attachments: uploadItems.value.map((i) => ({ name: i.name, url: i.url })) } as any })
  if (fErr) { ElMessage.error('记跟进失败：' + ((fErr as any)?.message ?? '')); return }
  // b. 可选上链存证（DELIVERY）
  if (uploadEvidence.value) {
    const { error: eErr } = await api.POST('/cases/{id}/evidence', { params: { path: { id } }, body: { scene: 'DELIVERY', refIds: uploadItems.value.map((i) => i.id), note: uploadNote.value || undefined } as any })
    if (eErr) { ElMessage.error('已记跟进，但存证失败：' + ((eErr as any)?.message ?? '')); uploadDlg.value = false; loadAll(); return }
    ElMessage.success('已记入跟进并发起送达存证（DELIVERY · 进存证清单）')
  } else {
    ElMessage.success('已上传并记入跟进')
  }
  uploadDlg.value = false; loadAll()
}

// 催收单打印：生成本案催收通知单并调起浏览器打印
const collectionDlg = ref(false)
function printCollection() { collectionDlg.value = true }
function doPrintCollection() { window.print() }

// 证据材料打包（整案 zip，可选同步上链存证）
const packDlg = ref(false)
const packDoEvidence = ref(true)
const packPreview = ref(false)
const packItems = ref<Record<string, boolean>>({ timeline: true, recording: true, transcript: true, ticket: false })
const packFileTag = computed(function () { var c: any = d.value?.case ?? {}; return (c.ownerName ?? '业主') + '_' + (c.room ?? '') })
const packAny = computed(function () { return Object.values(packItems.value).some(Boolean) })
function openEvidencePack() { packPreview.value = false; packDlg.value = true }
function submitEvidencePack() {
  if (!packAny.value) return
  var n = Object.values(packItems.value).filter(Boolean).length
  ElMessage.success('已发起证据打包（' + n + ' 项' + (packDoEvidence.value ? ' · 同步上链存证（按次计费）' : '') + '）')
  packDlg.value = false
}

// AI 采纳联动（校验动作权限）
const ADOPT: any = { PROMISE: ['promise', '登记承诺', 'case.promise'], TICKET: ['ticket', '转工单', 'case.ticket'], PAYLINK: ['paylink', '发缴费链接', 'case.paylink'], FOLLOWUP: ['follow', '写跟进', 'case.follow'] }
function adopt(card: any) {
  const m = ADOPT[card.actionRef]; if (!m) { ElMessage.info('该建议无联动动作'); return }
  if (!auth.has(m[2])) { ElMessage.warning('无权限：' + m[2]); return }
  if (m[0] === 'paylink') { openPaylink(m[1] + '（采纳 AI 建议）', card.id); return }
  openAct(m[0], m[1] + '（采纳 AI 建议）', card.id)
}

// caseId 变化（工作台「今日必办」切选另一案）：重置会话态并重新拉取，避免残留上一案子的临时状态
watch(() => props.caseId, (newId) => {
  if (!newId || newId === id) return
  id = newId
  d.value = null
  promises.value = []; tickets.value = []; legalDocs.value = []; repays.value = []
  readyRecs.value = []; payLinks.value = []; paylinkResult.value = null; paylinkLoading.value = false; dlgError.value = ''
  latest.value = null; reviewOpen.value = false; reviewRecId.value = ''; playbookDoc.value = null
  midTab.value = 'playbook'; tlFilter.value = 'all'
  dlg.value = { open: false, kind: '', title: '' }
  loadAll(); loadCloseReasons()
})

onMounted(function () { loadAll(); loadCloseReasons() })
</script>

<template>
  <div v-if="d" class="case3">
    <!-- ============ 左栏：业主画像 ============ -->
    <div class="col left">
      <div class="portrait-top">
        <div class="portrait-av" :style="{ background: avatarBg }">{{ ownerInitial }}</div>
        <div class="portrait-id">
          <div class="nm">{{ d.case?.ownerName || '—' }}</div>
          <div class="sub">{{ d.case?.room || '—' }} · {{ d.case?.phone || d.contacts?.[0]?.phone || '—' }}</div>
        </div>
        <div class="portrait-amt">
          <div class="a num">{{ yuan(d.case?.dueCents) }}</div>
          <div class="s">{{ d.case?.arrearagsPeriods?.length ? '欠 ' + d.case.arrearagsPeriods.length + ' 个月' : '应收欠费' }}</div>
        </div>
      </div>
      <!-- 状态徽标 + 风险标签 -->
      <div class="ptags" style="margin-top:12px">
        <span class="tag" :class="caseStatusTag(d.case?.status)" :title="d.case?.status">{{ caseStatusLabel(d.case?.status) }}</span>
        <span v-if="d.case?.pool" class="tag inf" :title="d.case.pool">{{ poolLabel(d.case.pool) }}</span>
        <span v-for="t in riskTags" :key="t" class="tag war" style="margin-left:4px">{{ t }}</span>
        <span v-if="redacted" class="tag inf">已脱敏</span>
      </div>
      <div class="pstats" style="margin-top:12px">
        <div><div class="v num">{{ (d.contacts ?? []).length }}</div><div class="k">联系次数</div></div>
        <div><div class="v num">{{ promises.length }}</div><div class="k">承诺次数</div></div>
        <div><div class="v num">{{ tickets.length }}</div><div class="k">工单数</div></div>
      </div>

      <!-- 欠费详情 -->
      <div class="sec-title">欠费详情</div>
      <table class="arrears"><tbody>
        <tr>
          <td>欠费周期</td>
          <td class="r" style="color:var(--reg);font-weight:400">
            <template v-if="(d.case?.arrearagePeriods ?? []).length">
              {{ d.case.arrearagePeriods[0] }} ~ {{ d.case.arrearagePeriods[d.case.arrearagePeriods.length - 1] }}
              <span style="font-size:11px;color:var(--sec);margin-left:4px">（{{ d.case.arrearagePeriods.length }} 个月）</span>
            </template>
            <template v-else>—</template>
          </td>
        </tr>
        <!-- 滞纳金=导入拆分真值(V920)：本金=due-penalty，合计=due；未拆分(NULL)时物业费即合计、滞纳金显示 — -->
        <tr><td>物业费</td><td class="r">{{ yuan(d.case?.penaltyCents != null ? (d.case?.dueCents ?? 0) - d.case.penaltyCents : d.case?.dueCents) }}</td></tr>
        <tr><td>滞纳金</td><td class="r" style="color:var(--danger)">{{ d.case?.penaltyCents != null ? yuan(d.case.penaltyCents) : '—' }}</td></tr>
        <tr><td><b>合计</b></td><td class="r"><b>{{ yuan(d.case?.dueCents) }}</b></td></tr>
        <tr v-if="d.case?.reduceAfterCents != null && d.case.reduceAfterCents !== d.case.dueCents"><td>减免后</td><td class="r" style="color:var(--success)">{{ yuan(d.case.reduceAfterCents) }}</td></tr>
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
        <!-- 协调员：「待办事项」置于最左（对标原型 PC Tab 顺序 待办事项 → 作战手册 → 沟通记录 → 项目资料） -->
        <div v-if="isCoordinator" class="t" :class="{ on: midTab === 'collab' }" @click="midTab = 'collab'">待办事项<span v-if="collabPendingCount" class="tag war" style="font-size:10px;padding:0 5px;margin-left:4px">{{ collabPendingCount }}</span></div>
        <div class="t" :class="{ on: midTab === 'playbook' }" @click="midTab = 'playbook'">作战手册</div>
        <div v-if="isCollector" class="t" :class="{ on: midTab === 'collab' }" @click="midTab = 'collab'">协调员处理<span v-if="collabDoneCount" class="tag suc" style="font-size:10px;padding:0 5px;margin-left:4px">{{ collabDoneCount }}</span></div>
        <div class="t" :class="{ on: midTab === 'timeline' }" @click="midTab = 'timeline'">沟通记录</div>
        <div class="t" :class="{ on: midTab === 'project' }" @click="midTab = 'project'">项目资料</div>
      </div>

      <!-- Tab·协调员处理（催收员 CO）：转协调员的工单及处理结果（对标原型 §协调员处理 caseTodoList） -->
      <div class="midpanel" v-show="midTab === 'collab'" v-if="isCollector">
        <div class="alert info" style="margin-top:0">你转协调员处理的事项（工单 / 减免 / 线下回款 / 建议法务）及协调员处理结果；结果也会推到你的消息中心。</div>
        <div class="tl" style="margin-top:10px" v-if="tickets.length">
          <div v-for="t in tickets" :key="t.id" style="padding:10px 0;border-bottom:1px solid var(--bd)">
            <div style="display:flex;align-items:center;gap:8px">
              <span class="tag war">工单 · {{ t.type || '—' }}</span>
              <span class="tag" :class="t.status === 'HANDLED' ? 'suc' : 'war'" style="font-size:11px">{{ t.status === 'HANDLED' ? '协调员已处理' : '待协调员处理' }}</span>
              <span v-if="t.createdAt" style="margin-left:auto;font-size:12px;color:var(--sec)">发起：{{ String(t.createdAt).slice(5, 16).replace('T', ' ') }}</span>
            </div>
            <div v-if="t.note" style="font-size:13px;color:var(--reg);margin:6px 0;line-height:1.7">诉求：{{ t.note }}</div>
            <div v-if="t.status === 'HANDLED'" class="note" style="font-size:12px;background:#f0f9eb;border:1px solid #c2e7b0;border-radius:6px;padding:7px 9px;color:var(--reg);margin-top:6px">
              ✓ 协调员处理结果：{{ t.result || '已处理' }}<span v-if="t.handledAt" style="color:var(--ph);margin-left:6px">— {{ String(t.handledAt).slice(0, 16).replace('T', ' ') }}</span>
            </div>
          </div>
        </div>
        <div v-else style="text-align:center;padding:36px 0;color:var(--ph)">
          <div style="font-size:34px">🤝</div>
          <div class="note" style="margin-top:8px">本案暂无转协调员处理的事项。</div>
        </div>
      </div>

      <!-- Tab·待办事项（协调员 PC）：本案需协调员处理的协作项，处理后自动回执催收员（对标原型 §待办事项） -->
      <div class="midpanel" v-show="midTab === 'collab'" v-if="isCoordinator">
        <div class="alert info" style="margin-top:0">本案需你处理的协作项（催收员转来）。处理后将<b>自动回执催收员</b>（消息中心 + 时间线）。</div>
        <div class="tl" style="margin-top:10px" v-if="tickets.length">
          <div v-for="t in tickets" :key="t.id" style="padding:10px 0;border-bottom:1px solid var(--bd)">
            <div style="display:flex;align-items:center;gap:8px">
              <span class="tag war">工单 · {{ t.type || '—' }}</span>
              <span class="tag" :class="t.status === 'HANDLED' ? 'suc' : 'war'" style="font-size:11px">{{ t.status === 'HANDLED' ? '已处理' : '待处理' }}</span>
              <span v-if="t.createdAt" style="margin-left:auto;font-size:12px;color:var(--sec)">发起：{{ String(t.createdAt).slice(5, 16).replace('T', ' ') }}</span>
            </div>
            <div v-if="t.note" style="font-size:13px;color:var(--reg);margin:6px 0 8px;line-height:1.7">{{ t.note }}</div>
            <button v-if="t.status !== 'HANDLED'" class="btn sm" @click="openHandle(t)">处理并回执催收员</button>
            <span v-else class="tag suc" style="font-size:11px">✓ 已处理并回执{{ t.result ? '：' + t.result : '' }}</span>
          </div>
        </div>
        <div v-else style="text-align:center;padding:36px 0;color:var(--ph)">
          <div style="font-size:34px">✅</div>
          <div class="note" style="margin-top:8px">本案暂无待处理协作项。</div>
        </div>
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
            class="e clickable"
            v-for="ev in tlFiltered"
            :key="ev.id"
            @click="openTlDetail(ev)"
          >
            <span class="ty" :class="tlTy(ev.type)">{{ tlLabel(ev.type) }}</span>
            <span class="tm">{{ ev.createdAt }}</span>
            {{ ev.content }}
            <span v-if="ev.actor" style="color:var(--sec);font-size:12px"> · {{ ev.actor }}</span>
            <span class="td-arr">›</span>
          </div>
        </div>
        <div v-else class="note">暂无记录。</div>
      </div>

      <!-- Tab2：项目资料（projectRef）·对齐高保真§项目档案/收费标准/收款信息/减免规则/批次信息 -->
      <div class="midpanel" v-show="midTab === 'project'">
        <!-- 项目档案 -->
        <div class="sec-title" style="margin-top:0">项目档案</div>
        <div class="desc">
          <div class="r"><div class="k">项目名称</div><div class="v">{{ d.case?.projectName || '—' }}</div></div>
          <div class="r" v-if="projectRef.contractName"><div class="k">物业合同</div><div class="v">{{ projectRef.contractName }}</div></div>
          <div class="r" v-if="projectRef.servicePeriod"><div class="k">服务期限</div><div class="v">{{ projectRef.servicePeriod }}</div></div>
        </div>
        <!-- 收费标准（收费依据） -->
        <div class="sec-title">收费标准（收费依据）</div>
        <div class="desc">
          <div class="r" v-if="projectRef.feeCycle"><div class="k">缴费周期</div><div class="v">{{ projectRef.feeCycle }}</div></div>
          <div class="r" v-if="projectRef.feeStd"><div class="k">物业费标准</div><div class="v">{{ projectRef.feeStd }}<span style="color:var(--sec);font-size:11px;margin-left:4px">（文字描述，非算金额源）</span></div></div>
          <div class="r" v-if="projectRef.feeItems"><div class="k">费项构成</div><div class="v">{{ projectRef.feeItems }}</div></div>
          <div class="note" v-if="!projectRef.feeCycle && !projectRef.feeStd && !projectRef.feeItems" style="font-size:12px">暂无收费标准信息。</div>
        </div>
        <!-- 收款信息（催收依据） -->
        <div class="sec-title">收款信息（催收依据）</div>
        <div class="desc">
          <div class="r" v-if="projectRef.corpAccount"><div class="k">对公账户</div><div class="v">{{ projectRef.corpAccount }}</div></div>
          <div class="r" v-if="projectRef.wxQrUrl"><div class="k">微信收款码</div><div class="v"><a :href="projectRef.wxQrUrl" target="_blank" style="color:var(--primary)">查看收款码</a></div></div>
          <div class="r" v-else-if="!projectRef.corpAccount"><div class="k">微信收款码</div><div class="v" style="color:var(--sec)">查看收款码</div></div>
          <div class="note" v-if="!projectRef.corpAccount && !projectRef.wxQrUrl" style="font-size:12px">暂无收款信息。</div>
        </div>
        <!-- 减免规则 -->
        <div class="sec-title">减免规则</div>
        <div class="desc">
          <div class="r" v-if="projectRef.reducePolicy"><div class="k">减免政策</div><div class="v">{{ projectRef.reducePolicy }}</div></div>
          <template v-if="(projectRef.reduceTiers ?? []).length">
            <div class="r"><div class="k">本案适用</div><div class="v">
              <span v-for="(t, ti) in projectRef.reduceTiers" :key="ti">
                <span v-if="ti > 0">；</span>{{ t.discount }}
                <span v-if="t.waivePenalty" class="tag suc" style="margin-left:4px">免滞纳金</span>
                <span v-if="t.capCents != null" class="tag inf" style="margin-left:4px">上限{{ yuan(t.capCents) }}</span>
              </span>
            </div></div>
          </template>
          <div class="note" v-if="!projectRef.reducePolicy && !(projectRef.reduceTiers ?? []).length" style="font-size:12px">本项目暂无减免规则。</div>
        </div>
        <!-- 批次信息 · 佣金比例按视角（资金双线隔离 BR-M9-11：收佣线仅物业+平台、付佣线仅服务商+平台；服务端已字段级脱敏） -->
        <div class="sec-title">批次信息</div>
        <div class="desc">
          <div class="r"><div class="k">批次号</div><div class="v">{{ projectRef.batchNo || d.case?.batchId || '—' }}</div></div>
          <!-- 物业(PL/PC)：付佣比例=物业→平台（该批次要付给平台的佣金）+ 平台确认态 -->
          <div class="r" v-if="isPropertyOrg && projectRef.commInRate"><div class="k">付佣比例</div><div class="v">
            <span class="tag war">{{ projectRef.commInRate }}（物业付平台）</span>
            <span class="tag" :class="projectRef.commInConfirmed ? 'suc' : 'inf'" style="margin-left:4px">{{ projectRef.commInConfirmed ? '平台已确认' : '待平台确认' }}</span>
          </div></div>
          <!-- 服务商负责人(VL)：收佣比例=平台→服务商（平台支付给本商的佣金） -->
          <div class="r" v-if="role === 'VL' && projectRef.payOutRate"><div class="k">收佣比例</div><div class="v"><span class="tag suc">{{ projectRef.payOutRate }}（平台付服务商）</span></div></div>
          <!-- 平台(SA/SE)：双线全见 + 毛利 -->
          <template v-if="isPlatformRole">
            <div class="r" v-if="projectRef.commInRate"><div class="k">收佣比例</div><div class="v"><span class="tag war">{{ projectRef.commInRate }}（平台↔物业）</span><span class="tag" :class="projectRef.commInConfirmed ? 'suc' : 'inf'" style="margin-left:4px">{{ projectRef.commInConfirmed ? '已确认' : '待确认' }}</span></div></div>
            <div class="r" v-if="projectRef.payOutRate"><div class="k">付佣比例</div><div class="v"><span class="tag suc">{{ projectRef.payOutRate }}（平台↔服务商）</span></div></div>
            <div class="r" v-if="grossMargin"><div class="k">平台毛利</div><div class="v"><span class="tag pri">{{ grossMargin }}</span></div></div>
          </template>
          <!-- 催收员(CO)：提成比例（服务商内部设定）见我的业绩 -->
          <div class="r" v-if="isCollector"><div class="k">我的提成比例</div><div class="v" style="color:var(--sec)">见「我的业绩」（服务商内部设定）</div></div>
        </div>
      </div>

      <!-- Tab3：作战手册（AI 动态沟通策略置顶 + AI 复盘 + 静态 playbook，对标原型 §作战手册） -->
      <div class="midpanel" v-show="midTab === 'playbook'">
        <!-- 动态：AI 沟通策略（通话前优先看，置顶） -->
        <template v-if="preCall">
          <div class="sec-title" style="margin-top:0">AI 动态「沟通策略与注意事项」 <span style="font-size:12px;font-weight:400;color:var(--sec)">据本案历史沟通 + 画像 + 话术库实时生成，仅建议、不强制</span></div>
          <div v-if="preCallBg" class="bgbox">📋 {{ preCallBg }}</div>
          <div v-for="(w, wi) in preCallWarns" :key="wi" class="riskbar" :class="w.includes('爽约') ? 'l2' : 'l1'">⚠ {{ w }}</div>
          <div class="aicard" :class="c.actionRef === 'PROMISE' ? 'script' : 'obj'" v-for="c in preCallCards" :key="c.id" style="margin-top:8px">
            <div class="h">
              <span>💡 {{ c.title }}</span>
              <span v-if="c.confidence" class="tag pri">置信度 {{ confLabel(c.confidence) }}</span>
            </div>
            <div class="tx">{{ c.body }}</div>
            <div v-if="c.trigger" class="note" style="font-size:11px;margin-top:4px">适用：{{ c.trigger }}</div>
            <div class="cta">
              <el-button v-if="c.actionRef && c.actionRef !== 'NONE'" size="small" type="primary" @click="adopt(c)">✓ 采纳</el-button>
              <el-button size="small" @click="dismissCard(c)">✗ 忽略</el-button>
            </div>
          </div>
        </template>

        <!-- AI 通话复盘统一走右侧复盘面板（AiReviewPanel）：右栏「查看并标注」或沟通记录点通话项打开 -->
        <div v-if="!preCall" class="alert info" style="margin-top:0">通话后在右侧操作区「查看并标注（AI 复盘）」或在沟通记录点通话项，打开本次通话的 AI 复盘面板（对话记录/结果标记/风险/建议）。</div>

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
        <!-- 操作区（对标原型：OPS 按权限过滤 + 分组渲染） -->
        <div style="font-size:13px;font-weight:600;color:var(--txt);margin-bottom:10px;display:flex;align-items:center;gap:6px">
          <span class="bar" style="width:3px;height:13px;background:var(--primary);border-radius:2px;display:inline-block"></span>操作区 · {{ roleName(role) || '—' }}
        </div>

        <!-- 录音区（全角色可见：获取最新录音+AI复盘+手动上传救济） -->
        <div class="op-rec" style="margin-bottom:12px">
          <div class="op-rec-h" style="font-weight:600;font-size:13px;margin-bottom:6px">本次通话回填</div>
          <div class="note" style="font-size:11px;margin:0 0 8px;line-height:1.6">ⓘ 平台不感知拨打时机；按作战手册通话后，点下方拉取本机最新录音（App 自动上传解析）。</div>
          <button v-if="!recObj" class="btn sm" style="width:100%" @click="getLatest">🔄 获取最新通话录音</button>
          <div v-if="recReady" class="rec-ready" style="margin-top:8px">
            <div class="rr-meta">
              <span class="tag" :class="recStatusTag(recObj.status)" :title="recObj.status">{{ callRecStatusLabel(recObj.status) }}</span>
              最新通话 · {{ recObj.durationSec || '—' }}s
            </div>
            <button v-if="recObj.status === 'READY'" class="btn sm" style="width:100%;margin-top:7px" @click="loadReview(recObj.id)">查看并标注（AI 复盘）→</button>
            <!-- 回听录音音频（BR-M4-01b · 全角色，复用 RecordingAudioPlayer） -->
            <RecordingAudioPlayer v-if="recObj?.id" :recording-id="String(recObj.id)" style="margin-top:7px" />
            <div class="toolbar" style="margin-top:7px;gap:6px;flex-wrap:wrap">
              <el-button size="small" @click="router.push(`/cases/${id}/call/${recObj.id}`)">通话详情</el-button>
              <el-button v-if="recObj.status === 'FAILED'" size="small" @click="reprocessRec(recObj.id)">重新处理</el-button>
              <el-button v-if="recObj.status === 'QUOTA_BLOCKED'" size="small" type="warning" @click="parseRec(recObj.id)">补解析</el-button>
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

        <!-- 操作按钮（按 OPS 分组，仅显示有权限的） -->
        <template v-if="caseOps.length">
          <div v-for="g in groupedCaseOps" :key="g.key" style="margin-bottom:10px">
            <div class="sec-title" style="margin:6px 0 6px">{{ g.label }}</div>
            <button class="btn sm" :class="o.cls" style="width:100%;margin-bottom:6px" v-for="o in g.items" :key="o.p + o.act" @click="onOp(o)">{{ o.label }}</button>
          </div>

          <template v-if="payLinks.length">
            <div class="sec-title" style="margin:10px 0 6px">缴费链接（本会话）</div>
            <div v-for="l in payLinks" :key="l.id" style="background:#fff;border:1px solid var(--bd);border-radius:8px;padding:10px 12px;margin-bottom:8px;box-shadow:0 1px 3px rgba(20,40,90,.04)">
              <div style="display:flex;align-items:center;gap:8px">
                <span class="num" style="font-size:15px;font-weight:700;color:var(--txt)">{{ yuan(l.amountCents) }}</span>
                <span class="tag" :class="l.status === 'ACTIVE' ? 'suc' : 'inf'" style="margin-left:auto">{{ l.status === 'ACTIVE' ? '有效' : '已失效' }}</span>
              </div>
              <div style="font-size:11px;color:var(--sec);margin:2px 0 8px">有效期至 {{ l.expiresAt ? String(l.expiresAt).slice(0, 16).replace('T', ' ') : '—' }}</div>
              <PayLinkCard :token="l.token" compact @send-sms="resendLink(l)">
                <div v-if="l.status === 'ACTIVE'" style="display:flex;align-items:center;gap:6px">
                  <a class="btn txt" style="padding:0;font-size:12px;color:var(--danger,#F56C6C)" @click="voidLink(l)">作废链接</a>
                  <span style="font-size:11px;color:var(--sec)">业主访问即失效</span>
                </div>
              </PayLinkCard>
            </div>
          </template>

          <!-- 危险操作（终态操作·不可撤销） -->
          <div v-if="dangerOps.length" style="margin-top:14px;padding-top:12px;border-top:1px solid var(--bd)">
            <div class="sec-title" style="color:var(--dg,#F56C6C);margin:0 0 6px">终态操作（不可撤销）</div>
            <button class="btn sm dg" style="width:100%" v-for="o in dangerOps" :key="o.p + o.act" @click="onOp(o)">{{ o.label }}</button>
          </div>
        </template>

        <div v-if="!caseOps.length" class="note" style="margin:8px 0 0">当前角色暂无可操作权限。</div>

        <!-- CO 法务只读（始终显示，不受 caseOps 有无影响） -->
        <template v-if="isCollector && d.case?.legalStage && d.case.legalStage !== 'NONE'">
          <div class="sec-title" style="margin-top:14px">法务进度</div>
          <div class="alert info" style="margin-top:0;font-size:12px">本案已进入法务流程 · 「{{ legalStageLabel(d.case.legalStage) }}」（只读，由协调员主导）。</div>
        </template>

        <!-- 送达存证（PL/PC 专属：始终显示） -->
        <template v-if="isPropertyRole">
          <div class="sec-title" style="margin-top:14px">文书出具（平台统一模板 · 物业信息自动填充）</div>
          <button class="btn df sm" style="width:100%;margin-bottom:6px" @click="printCollection">🖨 催收单（打印 / 下载 Word）</button>
          <button class="btn df sm" style="width:100%;margin-bottom:8px" @click="openSuitPreview">📄 诉讼文件（要素预览 / 下载 Word）</button>
          <div class="sec-title" style="margin-top:8px">上传与存证</div>
          <div class="note" style="font-size:11px;margin-bottom:6px">上传送达凭证/沟通材料即记入跟进；可按情况决定是否上链存证。</div>
          <button class="btn df sm" style="width:100%;margin-bottom:6px" @click="openUpload">📎 上传文件 / 凭证（可选存证）</button>
          <button class="btn df sm" style="width:100%;margin-bottom:8px" @click="openEvidencePack">⬇ 证据下载（整案打包）</button>
          <button class="btn pl sm" style="width:100%;margin-bottom:6px" @click="router.push('/evidence')">🔒 存证清单（全部）</button>
        </template>
      </div>
    </div>

    <!-- 生成诉讼文件（诉状要素预览）·对标原型 §suitDialog -->
    <el-dialog v-model="suitDlg" title="诉状要素预览 · 物业服务合同纠纷" width="760px" append-to-body>
      <div :class="['alert', suitMissing.length ? 'warn' : 'info']" style="margin-top:0">
        <template v-if="suitMissing.length">缺 {{ suitMissing.length }} 项要素（下方标红）：{{ suitMissing.join('、') }}。建议到「项目管理」补全后再生成正式 PDF。</template>
        <template v-else>要素齐全，可生成正式诉状 PDF（平台出具）。</template>
      </div>
      <div id="suit-print" style="font-size:13px;line-height:2.1;background:#fff;border:1px solid var(--bd);border-radius:8px;padding:18px;margin-top:10px;color:var(--txt);max-height:56vh;overflow:auto">
        <div style="text-align:center;font-size:17px;font-weight:700;margin-bottom:14px">民事起诉状</div>
        <p style="margin:0 0 10px"><b>原告：</b>{{ suitData.plaintiff || '物业公司' }}，统一社会信用代码 <em :class="{ miss: !suitData.creditCode }">{{ suitData.creditCode || '【信用代码缺失】' }}</em>，住所地 <em :class="{ miss: !suitData.plaintiffAddr }">{{ suitData.plaintiffAddr || '【物业地址缺失】' }}</em>，法定代表人 <em :class="{ miss: !suitData.legalRep }">{{ suitData.legalRep || '【法定代表人缺失】' }}</em>。</p>
        <p style="margin:0 0 10px"><b>被告：</b><em :class="{ miss: !suitData.defendant }">{{ suitData.defendant || '【业主姓名缺失】' }}</em>，身份证号 <em :class="{ miss: !suitData.defendantId }">{{ suitData.defendantId || '【身份证号缺失】' }}</em>，房屋坐落 <em :class="{ miss: !suitData.estate }">{{ suitData.estate || '【房屋坐落缺失】' }}</em>，电话 {{ suitData.defendantPhone || '—' }}。</p>
        <p style="margin:0 0 6px"><b>诉讼请求：</b></p>
        <p style="margin:0 0 4px">1. 判令被告支付拖欠物业服务费 <em :class="{ miss: !suitData.arrears }">{{ suitData.arrears || '【欠费金额缺失】' }}</em>（欠费周期 <em :class="{ miss: !suitData.arrearsPeriod }">{{ suitData.arrearsPeriod || '【欠费周期缺失】' }}</em>）；</p>
        <p style="margin:0 0 4px">2. 判令被告支付违约金（{{ suitData.penalty || '按约定' }}，计至实际清偿之日）；</p>
        <p style="margin:0 0 10px">3. 本案诉讼费由被告承担。</p>
        <p style="margin:0 0 10px"><b>事实与理由：</b>原告依据《<em :class="{ miss: !suitData.contractType }">{{ suitData.contractType || '【合同类型缺失】' }}</em>》为 {{ suitData.projectName || '本小区' }} 提供物业服务，收费标准 <em :class="{ miss: !suitData.feeStd }">{{ suitData.feeStd || '【收费标准缺失】' }}</em>，收费周期 {{ suitData.feeCycle || '—' }}。被告系上述房屋业主，自欠费周期起拖欠物业费 {{ suitData.arrears || '—' }}，经多次催告（见催收时间线、通话录音转写、送达签收存证）仍未支付，依法应承担给付义务。</p>
        <p style="margin:0 0 6px"><b>证据清单：</b></p>
        <p style="margin:0">① 物业服务合同及附件；② 收费标准/业主大会决议；③ 催收时间线 + 通话录音及转写 + 送达签收存证（M4/M6 存证打包）；④ 房屋坐落及业主信息。</p>
        <p style="margin:14px 0 0;text-align:right">此致　人民法院</p>
        <p style="margin:4px 0 0;text-align:right">具状人：{{ suitData.plaintiff || '物业公司' }}</p>
      </div>
      <div class="note" style="margin-top:8px">要素来源：原告/收费/合同 ← 项目管理；被告/欠费金额/周期 ← 案件明细；证据包 ← M4/M6。</div>
      <template #footer>
        <el-button @click="suitDlg = false">关闭</el-button>
        <el-button v-if="auth.has('proj.edit')" @click="suitDlg = false; router.push('/projects')">去项目管理补全</el-button>
        <el-button type="primary" :disabled="suitMissing.length > 0" @click="downloadSuitDoc">⬇ 下载诉状 Word</el-button>
      </template>
    </el-dialog>

    <!-- 催收单打印（催收通知单）·对标原型 催收单打印 -->
    <el-dialog v-model="collectionDlg" title="催收通知单 · 打印预览" width="620px" append-to-body>
      <div id="collection-print" style="background:#fff;border:1px solid var(--bd);border-radius:8px;padding:22px;font-size:13px;line-height:2;color:var(--txt)">
        <div style="text-align:center;font-size:18px;font-weight:700;margin-bottom:16px">物业费催收通知单</div>
        <p style="margin:0 0 6px">尊敬的 <b>{{ suitData.defendant || '业主' }}</b>（{{ suitData.estate || '' }}）：</p>
        <p style="margin:0 0 6px;text-indent:2em">经核查，您名下房屋自 {{ suitData.arrearsPeriod || '—' }} 起拖欠物业服务费 <b>{{ suitData.arrears || '—' }}</b>{{ suitData.penalty ? '，违约金 ' + suitData.penalty : '' }}。请您于收到本通知单起 <b>7 日内</b>缴清上述款项。</p>
        <p style="margin:0 0 6px;text-indent:2em">缴费方式：{{ (projectRef.corpAccount || projectRef.payInfo) ? '对公账户 / 微信收款码（详见缴费链接）' : '请联系物业前台' }}。逾期未缴，我司将依据《{{ suitData.contractType || '物业服务合同' }}》通过法律途径追缴，由此产生的诉讼费、违约金等由您承担。</p>
        <p style="margin:16px 0 0;text-align:right">{{ suitData.plaintiff || '物业公司' }}</p>
      </div>
      <template #footer>
        <el-button @click="collectionDlg = false">关闭</el-button>
        <el-button @click="downloadCollectionDoc">⬇ 下载 Word</el-button>
        <el-button type="primary" @click="doPrintCollection">🖨 打印</el-button>
      </template>
    </el-dialog>

    <!-- 上传文件/凭证：送达凭证/沟通材料上传 → 记跟进；协调员可勾选同时上链存证 -->
    <el-dialog v-model="uploadDlg" title="上传文件 / 凭证" width="480px" append-to-body>
      <div class="alert info" style="margin-top:0">上传业主签收照片 / 送达回执 / 沟通材料等（可选文件或扫码用手机上传）。提交后记入跟进时间线并进「送达管理」；如需固证再勾选下方“同时上链存证”。</div>
      <div style="margin-top:12px">
        <div class="lbl">送达类型（请先选类型再上传）</div>
        <el-select v-model="uploadDeliveryType" size="small" style="width:100%;margin-bottom:10px">
          <el-option v-for="t in DELIVERY_TYPES" :key="t.v" :label="t.label" :value="t.v" />
        </el-select>
        <AttachmentUpload :case-id="id" :delivery-type="uploadDeliveryType" @uploaded="onUploaded" />
      </div>
      <div v-if="uploadItems.length" style="margin-top:12px">
        <div class="lbl">已上传（{{ uploadItems.length }}）</div>
        <div v-for="(f, fi) in uploadItems" :key="fi" style="margin-top:6px;font-size:13px">
          ✅ <a :href="f.url" target="_blank" style="color:var(--pri,#2f6fed)">{{ f.name }}</a>
        </div>
      </div>
      <div style="margin-top:12px">
        <div class="lbl">备注（选填）</div>
        <input class="inp" v-model="uploadNote" placeholder="如：2026-07-06 上门送达并当面签收" style="width:100%">
      </div>
      <label v-if="auth.has('evidence.create')" style="display:flex;align-items:center;gap:8px;cursor:pointer;margin-top:12px">
        <input type="checkbox" v-model="uploadEvidence">
        <span style="font-size:13px">同时上链存证（易保全保全 · 按次计费）——需要作为证据固证时勾选</span>
      </label>
      <template #footer>
        <el-button @click="uploadDlg = false">取消</el-button>
        <el-button type="primary" :disabled="!uploadItems.length" @click="submitUpload">{{ uploadEvidence ? '记跟进并存证' : '记入跟进' }}</el-button>
      </template>
    </el-dialog>

    <!-- 证据材料打包（整案 zip）·对标原型 §evidencePackDialog -->
    <el-dialog v-model="packDlg" title="证据材料打包" width="540px" append-to-body>
      <div style="margin-bottom:14px">
        <div class="lbl" style="margin-bottom:6px">打包结果是否上链存证</div>
        <label style="display:flex;align-items:center;gap:8px;cursor:pointer"><input type="checkbox" v-model="packDoEvidence"> <span>打包完成后同步发起存证（按次计费）</span></label>
        <div class="note" style="margin-top:4px">关闭则仅生成 ZIP 不计存证费</div>
      </div>
      <div style="margin-bottom:14px">
        <div class="lbl" style="margin-bottom:8px">ZIP 文件清单（勾选需打包的文件）</div>
        <div style="display:flex;flex-direction:column;gap:8px">
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer"><input type="checkbox" v-model="packItems.timeline"> <span style="font-size:13px">时间线 PDF &nbsp;<span class="note" style="margin:0">timeline_{{ packFileTag }}.pdf</span></span></label>
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer"><input type="checkbox" v-model="packItems.recording"> <span style="font-size:13px">通话录音 &nbsp;<span class="note" style="margin:0">recording_{{ packFileTag }}.mp3</span></span></label>
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer"><input type="checkbox" v-model="packItems.transcript"> <span style="font-size:13px">转写文本 &nbsp;<span class="note" style="margin:0">transcript_{{ packFileTag }}.txt</span></span></label>
          <label style="display:flex;align-items:center;gap:8px;cursor:pointer"><input type="checkbox" v-model="packItems.ticket"> <span style="font-size:13px">工单回执 &nbsp;<span class="note" style="margin:0">ticket_{{ packFileTag }}.pdf（默认不勾）</span></span></label>
        </div>
      </div>
      <div style="margin-bottom:4px">
        <el-button size="small" @click="packPreview = !packPreview">{{ packPreview ? '收起预览' : '预览 ZIP 清单' }}</el-button>
        <div v-if="packPreview" style="margin-top:10px;border:1px solid var(--bd);border-radius:6px;padding:10px;background:#f8fafc">
          <div class="lbl" style="margin-bottom:6px">当前勾选文件</div>
          <ul style="margin:0;padding-left:18px;font-size:13px;line-height:1.8">
            <li v-if="packItems.timeline">timeline_{{ packFileTag }}.pdf</li>
            <li v-if="packItems.recording">recording_{{ packFileTag }}.mp3</li>
            <li v-if="packItems.transcript">transcript_{{ packFileTag }}.txt</li>
            <li v-if="packItems.ticket">ticket_{{ packFileTag }}.pdf</li>
            <li v-if="!packAny" style="color:var(--ph)">（未勾选任何文件）</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <el-button @click="packDlg = false">取消</el-button>
        <el-button type="primary" :disabled="!packAny" @click="submitEvidencePack">发起打包</el-button>
      </template>
    </el-dialog>

    <!-- ===================== 保留 EL 对话框（仅触发入口换到三栏） ===================== -->
    <!-- 通用动作对话框（对标原型各弹窗 alert 说明 + lbl 字段 + 校验态提交按钮） -->
    <DsDrawer v-model="dlg.open" :title="dlg.title" :width="480">
      <div v-if="dlgError" class="alert err" style="margin-top:0">⚠ 提交失败：{{ dlgError }}</div>
      <template v-if="dlg.kind==='follow'">
        <div class="alert info" style="margin-top:0">跟进记录写入案件时间线（手记），<b>不改变案件状态</b>；如需登记承诺/回款请用对应操作。</div>
        <div style="margin-top:10px"><div class="lbl">跟进方式</div>
          <select class="inp" v-model="form.method" style="width:100%">
            <option value="CALL">电话沟通</option><option value="SMS">短信</option><option value="WECHAT">微信</option>
            <option value="VISIT">上门</option><option value="OTHER">其他</option>
          </select>
        </div>
        <div style="margin-top:10px"><div class="lbl">跟进内容</div>
          <textarea class="ta" v-model="form.content" placeholder="记录本次跟进情况（必填）…"></textarea>
        </div>
        <div style="margin-top:10px">
          <div class="lbl">附件（图片 / 文件）</div>
          <AttachmentUpload :case-id="id" @uploaded="onFollowUploaded" />
          <div class="note" style="font-size:11px;margin-top:4px">直接选文件上传，或扫码用手机拍照/选文件上传；也可下方手填外链。</div>
          <button class="btn df sm" style="margin-top:6px" @click="addAttachment">+ 加外链附件</button>
          <div v-for="(a,ai) in form.attachments" :key="ai" style="margin-top:6px;display:flex;gap:6px;align-items:center">
            <input class="inp" v-model="a.name" placeholder="名称" style="width:110px">
            <input class="inp" v-model="a.url" placeholder="url" style="flex:1;min-width:0">
            <a class="btn txt" style="color:var(--danger,#F56C6C)" @click="removeAttachment(ai)">删除</a>
          </div>
        </div>
      </template>
      <template v-else-if="dlg.kind==='promise'">
        <div style="margin-bottom:12px"><div class="lbl" style="margin-bottom:6px">承诺类型</div>
          <span class="tag-pick" :class="{on:!form.installments?.length}" style="cursor:pointer;margin-right:8px" @click="form.installments=[]">单笔</span>
          <span class="tag-pick" :class="{on:!!form.installments?.length}" style="cursor:pointer" @click="addInstallment">分期</span>
        </div>
        <template v-if="!form.installments?.length">
          <div style="margin-bottom:10px"><div class="lbl">到期日</div><input class="inp" type="date" v-model="form.date" style="width:100%"></div>
          <div style="margin-bottom:10px"><div class="lbl">承诺金额（元）</div><input class="inp" type="number" v-model.number="form.amountYuan" style="width:100%"></div>
        </template>
        <template v-else>
          <div class="lbl" style="margin-bottom:6px">分期期次</div>
          <table style="width:100%;font-size:13px;margin-bottom:8px">
            <thead><tr><th>期次</th><th>到期日</th><th>金额（元）</th><th></th></tr></thead>
            <tbody>
              <tr v-for="(it,i) in form.installments" :key="i">
                <td>第{{ it.seq }}期</td>
                <td><input class="inp" type="date" v-model="it.dueDate" style="width:100%;min-width:140px"></td>
                <td><input class="inp" type="number" v-model.number="it.amountYuan" style="width:100%;min-width:100px"></td>
                <td><a class="btn txt" style="color:var(--danger,#F56C6C)" @click="removeInstallment(i)">删除</a></td>
              </tr>
            </tbody>
          </table>
          <button class="btn df sm" @click="addInstallment">+ 新增期次</button>
        </template>
      </template>
      <template v-else-if="dlg.kind==='ticket'">
        <div class="alert info" style="margin-top:0">转工单将转交物业协调员线下处理（上门核实/开证明等），协调员处理后回执；案件仍留在我的私海。</div>
        <div style="margin-top:10px"><div class="lbl">工单类型</div>
          <select class="inp" v-model="form.type" style="width:100%">
            <option v-for="t in TICKET_TYPES" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <div style="margin-top:10px"><div class="lbl">诉求 / 说明</div>
          <textarea class="ta" v-model="form.note" placeholder="请填写诉求说明（必填）"></textarea>
        </div>
      </template>
      <template v-else-if="dlg.kind==='repay'">
        <div style="margin-bottom:10px"><div class="lbl">回款金额（元）</div><input class="inp" type="number" v-model.number="form.amountYuan" style="width:100%"></div>
        <div style="margin-bottom:10px"><div class="lbl">回款日期</div><input class="inp" type="date" v-model="form.paidAt" style="width:100%"></div>
        <div style="margin-bottom:10px"><div class="lbl">回款渠道</div>
          <select class="inp" v-model="form.channel" style="width:100%">
            <option value="BANK_TRANSFER">对公转账</option>
            <option value="WECHAT_QR">线上缴费码</option>
            <option value="CASH">现金</option>
          </select>
        </div>
        <div><div class="lbl">凭证说明（选填）</div><textarea class="ta" v-model="form.note" placeholder="凭证说明 / 备注（可选）"></textarea></div>
      </template>
      <template v-else-if="dlg.kind==='reduce'">
        <div class="alert info" style="margin-top:0">按项目<b>减免政策（阶梯）</b>判定决定权：<b>催收员自决</b>档系统直接生效；<b>线下内部流程</b>档系统仅留痕、由物业内部线下处理。</div>
        <div v-if="reduceTiers.length" style="margin-top:10px">
          <div class="lbl">本项目减免政策</div>
          <table style="margin-top:4px">
            <thead><tr><th>折扣</th><th>减免上限</th><th style="text-align:center">含违约金</th><th>决定权</th></tr></thead>
            <tbody>
              <tr v-for="(t,i) in reduceTiers" :key="i">
                <td>{{ t.discount || '—' }}</td>
                <td>{{ t.capCents != null ? yuan(t.capCents) : '不限' }}</td>
                <td style="text-align:center">{{ t.waivePenalty ? '是' : '否' }}</td>
                <td><span class="tag" :class="t.decide==='COLLECTOR_SELF' ? 'suc' : 'war'">{{ reduceDecideLabel[t.decide] || t.decide }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="note" style="margin-top:10px">本项目暂未配置减免政策，默认 ≤¥500 催收员自决、超出走线下内部流程。</div>
        <div style="margin-top:10px"><div class="lbl">减免类型</div>
          <select class="inp" v-model="form.type" style="width:100%">
            <option>减免滞纳金</option><option>分期缴纳</option><option>金额减免</option><option>其他</option>
          </select>
        </div>
        <div style="margin-top:10px"><div class="lbl">减免金额（元）</div><input class="inp" type="number" v-model.number="form.amountYuan" style="width:100%" placeholder="输入减免金额"></div>
        <div style="margin-top:10px"><div class="lbl">减免原因</div><textarea class="ta" v-model="form.reason" placeholder="请填写减免原因（必填）"></textarea></div>
        <div v-if="reduceSelfService" class="alert ok" style="margin-top:10px">该减免（¥{{ form.amountYuan || 0 }}）属「催收员自决」档，可直接生效。</div>
        <div v-else class="alert warn" style="margin-top:10px">⚠ 该减免（¥{{ form.amountYuan || 0 }}）属「线下内部流程」档：系统不直接生效，将留痕并提示由物业内部线下处理。</div>
      </template>
      <template v-else-if="dlg.kind==='legal'">
        <div class="alert info" style="margin-top:0">申请法律文书（催款函 / 律师函 / 诉讼），平台用要素化数据套模板出具；<b>律师函/诉讼等法律服务按次付费（M9）</b>。</div>
        <div style="margin-top:10px"><div class="lbl">文书类型</div>
          <select class="inp" v-model="form.type" style="width:100%">
            <option value="COLLECTION_LETTER">催款函</option>
            <option value="LAWYER_LETTER">律师函</option>
            <option value="LITIGATION">诉讼</option>
          </select>
        </div>
      </template>
      <template v-else-if="dlg.kind==='close'">
        <div class="alert err" style="margin-top:0">⚠ 结案不可撤销，结案后按规则脱敏。请选择结案原因。</div>
        <div style="margin-top:10px"><div class="lbl">结案类型</div>
          <select class="inp" v-model="form.closeKind" style="width:100%" @change="form.reasonCode=''">
            <option value="WITHDRAWN">撤案</option><option value="BAD_DEBT">坏账</option>
          </select>
        </div>
        <div style="margin-top:10px"><div class="lbl">结案原因</div>
          <select class="inp" v-model="form.reasonCode" style="width:100%">
            <option value="">请选择结案原因</option>
            <option v-for="o in closeReasonOptions" :key="o.code" :value="o.code">{{ o.label }}</option>
          </select>
        </div>
        <div v-if="form.reasonCode==='OTHER'" style="margin-top:10px"><div class="lbl">备注说明</div><textarea class="ta" v-model="form.reasonNote" placeholder="其它原因说明"></textarea></div>
      </template>
      <template v-else-if="dlg.kind==='paylink'">
        <div v-if="paylinkLoading" class="note" style="text-align:center;padding:20px 0">正在生成缴费链接…</div>
        <template v-else-if="paylinkResult">
          <div class="alert ok" style="margin-top:0">缴费链接已生成 · {{ yuan(paylinkResult.amountCents) }}。这就是业主打开短信/微信里链接后看到的 H5 账单页，复制链接或下载二维码均可转发；确认好内容后再决定要不要短信发送。</div>
          <div style="margin-top:12px"><PayLinkCard :token="paylinkResult.token" preview @send-sms="sendSmsForPreview" /></div>
        </template>
      </template>
      <div v-if="form.sourceSuggestionId && dlg.kind!=='paylink'" style="margin-top:12px"><span class="tag suc">采纳 AI 建议 #{{ form.sourceSuggestionId }}</span></div>
      <template #footer>
        <template v-if="dlg.kind==='paylink'">
          <el-button type="primary" @click="dlg.open=false">完成</el-button>
        </template>
        <template v-else>
          <el-button @click="dlg.open=false">取消</el-button>
          <el-button
            type="primary"
            :disabled="(dlg.kind==='follow' && !form.content?.trim()) || (dlg.kind==='ticket' && !form.note?.trim()) || (dlg.kind==='reduce' && !form.reason?.trim()) || (dlg.kind==='close' && (!form.reasonCode || (form.reasonCode==='OTHER' && !form.reasonNote?.trim())))"
            @click="submitAct"
          >{{ dlg.kind==='reduce' ? (reduceSelfService ? '确认减免' : '提交线下流程（留痕）') : dlg.kind==='close' ? '确认结案' : '提交' }}</el-button>
        </template>
      </template>
    </DsDrawer>

    <!-- 工单处理 -->
    <DsDrawer v-model="hdlg" title="处理工单" :width="440">
      <el-form label-width="80px">
        <el-form-item label="处理结果"><el-input v-model="hForm.result" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="回执"><el-input v-model="hForm.receipt" placeholder="回执地址/说明" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="hdlg=false">取消</el-button><el-button type="primary" @click="submitHandle">提交</el-button></template>
    </DsDrawer>

    <!-- 通话结果标记 -->
    <!-- M-01: 标记码 SSOT 来自 CaseDetail.markCodes(enabled 项)，无来源回退 MARK_CODES_FALLBACK -->
    <DsDrawer v-model="mkdlg" title="通话结果标记" :width="400">
      <el-form label-width="80px"><el-form-item label="结果码"><el-select v-model="mkForm.mark"><el-option v-for="m in markCodes" :key="m.code" :label="m.label" :value="m.code" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="mkdlg=false">取消</el-button><el-button type="primary" @click="submitMark">标记</el-button></template>
    </DsDrawer>

    <!-- M-08: 新增联系人(标签 + 主号) -->
    <DsDrawer v-model="cdlg" title="新增联系人" :width="400">
      <el-form label-width="80px">
        <el-form-item label="电话"><el-input v-model="cForm.phone" placeholder="联系号码" /></el-form-item>
        <el-form-item label="标签"><el-select v-model="cForm.label"><el-option v-for="l in ['本人','配偶','亲属','单位','补充']" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="设为主号"><el-switch v-model="cForm.isPrimary" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitContact">提交</el-button></template>
    </DsDrawer>

    <!-- 沟通记录逐条详情抽屉（对标原型右侧抽屉：手记/工单/催费单/承诺/法务/存证/状态变更） -->
    <DsDrawer v-model="tldlg" :title="tlLabel(tlDetail?.type) + '详情'" :width="480">
      <template v-if="tlDetail">
        <div class="desc">
          <div class="r"><div class="k">类型</div><div class="v"><span class="ty" :class="tlTy(tlDetail.type)">{{ tlLabel(tlDetail.type) }}</span></div></div>
          <div class="r"><div class="k">时间</div><div class="v">{{ String(tlDetail.createdAt || '—').slice(0, 19).replace('T', ' ') }}</div></div>
          <div class="r"><div class="k">操作人</div><div class="v">{{ tlDetail.actor || '系统' }}</div></div>
        </div>
        <div class="sec-title">内容</div>
        <div style="background:#f8fafc;border:1px solid var(--bd);border-radius:6px;padding:12px;font-size:14px;line-height:1.8">{{ tlDetail.content || '—' }}</div>

        <!-- 关联对象详情（按 refType 从本案已加载数据回查） -->
        <template v-if="tlLinked?.data">
          <template v-if="tlLinked.kind === 'ticket'">
            <div class="sec-title">工单详情</div>
            <div class="desc">
              <div class="r"><div class="k">工单类型</div><div class="v">{{ tlLinked.data.type || '—' }}</div></div>
              <div class="r"><div class="k">诉求</div><div class="v">{{ tlLinked.data.note || '—' }}</div></div>
              <div class="r"><div class="k">状态</div><div class="v"><span class="tag" :class="tlLinked.data.status === 'HANDLED' ? 'suc' : 'war'">{{ tlLinked.data.status === 'HANDLED' ? '已处理' : '待处理' }}</span></div></div>
              <div class="r" v-if="tlLinked.data.result"><div class="k">处理结果</div><div class="v">{{ tlLinked.data.result }}</div></div>
              <div class="r" v-if="tlLinked.data.handledAt"><div class="k">处理时间</div><div class="v">{{ String(tlLinked.data.handledAt).slice(0, 16).replace('T', ' ') }}</div></div>
            </div>
          </template>
          <template v-else-if="tlLinked.kind === 'promise'">
            <div class="sec-title">承诺单</div>
            <div class="desc">
              <div class="r"><div class="k">承诺日期</div><div class="v">{{ tlLinked.data.date || '—' }}</div></div>
              <div class="r"><div class="k">承诺金额</div><div class="v">{{ yuan(tlLinked.data.amountCents) }}</div></div>
              <div class="r"><div class="k">履约状态</div><div class="v"><span class="tag" :class="tlLinked.data.state === 'FULFILLED' ? 'suc' : tlLinked.data.state === 'BROKEN' ? 'dan' : 'war'" :title="tlLinked.data.state">{{ promiseStateLabel(tlLinked.data.state) }}</span></div></div>
              <div class="r" v-if="tlLinked.data.installments?.length"><div class="k">分期</div><div class="v">{{ tlLinked.data.installments.length }} 期</div></div>
            </div>
          </template>
          <template v-else-if="tlLinked.kind === 'repay'">
            <div class="sec-title">回款明细</div>
            <div class="desc">
              <div class="r"><div class="k">回款金额</div><div class="v">{{ yuan(tlLinked.data.amountCents) }}</div></div>
              <div class="r"><div class="k">渠道</div><div class="v">{{ tlLinked.data.channel || '—' }}</div></div>
              <div class="r"><div class="k">到账日</div><div class="v">{{ tlLinked.data.paidAt || '—' }}</div></div>
              <div class="r" v-if="tlLinked.data.reversed"><div class="k">状态</div><div class="v"><span class="tag dan">已冲正</span></div></div>
            </div>
          </template>
          <template v-else-if="tlLinked.kind === 'legal'">
            <div class="sec-title">法务文书</div>
            <div class="desc">
              <div class="r"><div class="k">文书类型</div><div class="v" :title="tlLinked.data.type">{{ legalDocTypeLabel(tlLinked.data.type) }}</div></div>
              <div class="r"><div class="k">状态</div><div class="v"><span class="tag inf" :title="tlLinked.data.status">{{ legalDocStatusLabel(tlLinked.data.status) }}</span></div></div>
              <div class="r" v-if="tlLinked.data.deliveredAt"><div class="k">送达时间</div><div class="v">{{ String(tlLinked.data.deliveredAt).slice(0, 16).replace('T', ' ') }}</div></div>
            </div>
          </template>
          <template v-else-if="tlLinked.kind === 'paylink'">
            <div class="sec-title">催费单</div>
            <div class="desc">
              <div class="r"><div class="k">应缴金额</div><div class="v">{{ yuan(tlLinked.data.amountCents) }}</div></div>
              <div class="r"><div class="k">状态</div><div class="v"><span class="tag" :class="tlLinked.data.status === 'ACTIVE' ? 'suc' : 'inf'">{{ tlLinked.data.status === 'ACTIVE' ? '有效' : '已失效' }}</span></div></div>
            </div>
            <div style="margin-top:10px"><PayLinkCard :token="tlLinked.data.token" @send-sms="resendLink(tlLinked.data)" /></div>
          </template>
        </template>
      </template>
      <template #footer><el-button @click="tldlg = false">关闭</el-button></template>
    </DsDrawer>

    <!-- AI 复盘面板（统一布局：左对话记录 / 右小结+结果标记+风险+建议 / 底部存证+保存标注） -->
    <AiReviewPanel
      v-model:open="reviewOpen"
      :recording-id="reviewRecId"
      :case-id="id"
      :owner-name="d.case?.ownerName"
      :room="d.case?.room"
      :mark-codes="markCodes"
      :can-adopt="true"
      @adopt="onReviewAdopt"
    />
  </div>
</template>

<style scoped>
/* 诉状要素缺失标红（对标原型 s-el miss 高亮）；打印时仅保留催收通知单 */
.miss { color: var(--danger, #F56C6C); font-style: normal; font-weight: 600; }
@media print {
  body * { visibility: hidden; }
  #collection-print, #collection-print * { visibility: visible; }
  #collection-print { position: absolute; left: 0; top: 0; width: 100%; border: none; }
}
</style>
