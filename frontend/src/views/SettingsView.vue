<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { roleTemplateLabel, dataScopeLabel, settingsDomainLabel, scriptSourceLabel, scriptStatusLabel, closeKindLabel } from '../constants/enums'
import { DOMAIN_META, fieldLabel, fieldDesc, fieldValue } from '../constants/settingsMeta'
import { permLabel } from '../constants/permissions'
import DsDrawer from '../components/DsDrawer.vue'
import type { components } from '../api/schema'

type SettingsInput = components['schemas']['SettingsInput']
type CloseKind = components['schemas']['CloseKindEnum']

// 系统配置(平台)：GET /settings 列业务规则各域(带版本/生效时间，参数变更只对新计时案件生效 BR-M3-19)。
// M-09：全 5 域可编辑(TIMERS/ROTATION/MARK_CODES/CLOSE_REASONS/SMS)，复用通用 saveDomain。
const auth = useAuth()
const items = ref<any[]>([])
const fmt = (v: any) => v == null ? '—' : JSON.stringify(v)
const domainOf = (x: any) => x.timers ?? x.rotation ?? x.markCodes ?? x.closeReasons ?? x.sms

// 域内容摊平成「键→值」给人话表格用。数组型域（标记码/结案原因）本身就是一张清单，
// 包一层假键名反而绕，直接以域名作键展示整条清单。
function flatFields(row: any): Record<string, any> {
  const v = domainOf(row)
  if (v == null) return {}
  if (Array.isArray(v)) return { [row.domain === 'MARK_CODES' ? 'markCodes' : 'closeReasons']: v }
  return v
}

// AI 配置分组。live=这一组是否真的被代码读取——只有飞轮的 trigger 在 FlywheelService 里被读；
// llm/asr/prompts 存着但没人读（客户端 Phase 3 才写），必须在页面上说清楚，否则又是一个「配了不生效」的坑。
// v1.24.0：引擎已实现，是否「生效」取决于三方通道里有没有配 key 并启用——
// 不能再写死 false（那是上一版没引擎时的实情），也不能写死 true（没配 key 时它确实不干活）。
const asrLive = computed(() => integrations.value.some((i) => i.provider === 'BAILIAN' && i.enabled && i.configured))
const llmLive = computed(() => integrations.value.some((i) => i.provider === 'DEEPSEEK' && i.enabled && i.configured))
const aiLive = computed(() => asrLive.value || llmLive.value)
const AI_GROUPS = computed(() => [
  { key: 'flywheel', label: '话术飞轮', live: true },
  { key: 'llm', label: '大模型 LLM', live: llmLive.value },
  { key: 'asr', label: '语音转写 ASR', live: asrLive.value },
  { key: 'prompts', label: '提示词', live: llmLive.value },
])

async function load() {
  const { data, error } = await api.GET('/settings', {})
  if (error) { ElMessage.error('加载失败（仅平台可见）'); return }
  items.value = (data as any) ?? []
}
function domainData(domain: string) {
  const row = items.value.find((x) => x.domain === domain)
  return row ?? {}
}
// 卡头「编辑」→ 分发到对应抽屉。入口跟着卡片走，而不是页面顶部一排代码名按钮。
const matrixOpen = ref(false)
// 按业务顺序排：后端按域名字母序回，「结案原因」排第一很怪。
const DOMAIN_ORDER = ['TIMERS', 'ROTATION', 'MARK_CODES', 'CLOSE_REASONS', 'SMS']
const orderedItems = computed(() => [...items.value].sort(
  (a, b) => DOMAIN_ORDER.indexOf(a.domain) - DOMAIN_ORDER.indexOf(b.domain)))

function editDomain(domain: string) {
  if (domain === 'TIMERS') openTimers()
  else if (domain === 'ROTATION') openRotation()
  else if (domain === 'MARK_CODES') openMarkCodes()
  else if (domain === 'CLOSE_REASONS') openCloseReasons()
  else if (domain === 'SMS') openSms()
}

// 通用保存：PUT /settings body={domain, effectiveAt?, <域字段>:value}，写新版本(对新计时案件生效 BR-M3-19)。
// effectiveAt 可选(SettingsInput.effectiveAt)，留空=立即生效。
async function saveDomain(domain: SettingsInput['domain'], value: any, effectiveAt?: string | null) {
  const body: any = { domain }
  if (effectiveAt) body.effectiveAt = effectiveAt
  if (domain === 'TIMERS') body.timers = value
  else if (domain === 'ROTATION') body.rotation = value
  else if (domain === 'MARK_CODES') body.markCodes = value
  else if (domain === 'CLOSE_REASONS') body.closeReasons = value
  else if (domain === 'SMS') body.sms = value
  const { error } = await api.PUT('/settings', { body: body as any })
  if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return false }
  ElMessage.success('已保存新版本（对新计时案件生效 BR-M3-19）'); load(); return true
}

// ROTATION
const rotDlg = ref(false)
const rotForm = ref<any>({ holdCap: 50, maxRotations: 3, effectiveAt: null })
function openRotation() {
  const rot = domainData('ROTATION').rotation ?? {}
  rotForm.value = { holdCap: rot.holdCap ?? 50, maxRotations: rot.maxRotations ?? 3, effectiveAt: null }
  rotDlg.value = true
}
async function saveRotation() {
  if (rotForm.value.holdCap < 1 || rotForm.value.maxRotations < 0) { ElMessage.error('请填写非负合理值'); return }
  if (await saveDomain('ROTATION', { holdCap: rotForm.value.holdCap, maxRotations: rotForm.value.maxRotations }, rotForm.value.effectiveAt)) rotDlg.value = false
}

// TIMERS（CFG-TIMERS-DRAFT：T1=48h / T2=168h / TC=168h / MAXCYCLE=90天，非法/负数拒绝 US-M3-11）
const timerDlg = ref(false)
const timerForm = ref<any>({ t1Hours: 48, t2Hours: 168, tCollectorHours: 168, maxCycleDays: 90, effectiveAt: null })
function openTimers() {
  const t = domainData('TIMERS').timers ?? {}
  timerForm.value = {
    t1Hours: t.t1Hours ?? 48,
    t2Hours: t.t2Hours ?? 168,
    tCollectorHours: t.tCollectorHours ?? 168,
    maxCycleDays: t.maxCycleDays ?? 90,
    effectiveAt: null,
  }
  timerDlg.value = true
}
async function saveTimers() {
  const f = timerForm.value
  if (f.t1Hours < 0 || f.t2Hours < 0 || f.tCollectorHours < 0 || f.maxCycleDays < 0) {
    ElMessage.error('时效参数不能为负（US-M3-11/BR-M3-19）'); return
  }
  if (await saveDomain('TIMERS', {
    t1Hours: f.t1Hours, t2Hours: f.t2Hours, tCollectorHours: f.tCollectorHours, maxCycleDays: f.maxCycleDays,
  }, f.effectiveAt)) timerDlg.value = false
}

// SMS（cooldownMinutes 物业可见；signature/templates/warnThreshold 平台统一配置 BR-M9-09）
const smsDlg = ref(false)
const smsForm = ref<any>({ cooldownMinutes: 60, signature: '', warnThreshold: null, templates: [], effectiveAt: null })
function openSms() {
  const s = domainData('SMS').sms ?? {}
  smsForm.value = {
    cooldownMinutes: s.cooldownMinutes ?? 60,
    signature: s.signature ?? '',
    warnThreshold: s.warnThreshold ?? null,
    templates: Array.isArray(s.templates) ? s.templates.map((t: any) => ({ ...t })) : [],
    effectiveAt: null,
  }
  smsDlg.value = true
}
async function saveSms() {
  const f = smsForm.value
  if (f.cooldownMinutes < 0) { ElMessage.error('冷却分钟不能为负'); return }
  if (f.warnThreshold != null && f.warnThreshold < 0) { ElMessage.error('预警阈值不能为负'); return }
  if (await saveDomain('SMS', {
    cooldownMinutes: f.cooldownMinutes,
    signature: f.signature || null,
    warnThreshold: f.warnThreshold,
    templates: f.templates,
  }, f.effectiveAt)) smsDlg.value = false
}

// MARK_CODES（数组域，connected/effectiveFollowUp 结构须与读一致 BR-M4-12）
const markDlg = ref(false)
const markRows = ref<any[]>([])
const markEffectiveAt = ref<string | null>(null)
function openMarkCodes() {
  const arr = domainData('MARK_CODES').markCodes
  markRows.value = Array.isArray(arr) ? arr.map((m: any) => ({
    code: m.code ?? '', label: m.label ?? '', enabled: m.enabled !== false,
    connected: m.connected === true, effectiveFollowUp: m.effectiveFollowUp === true,
  })) : []
  markEffectiveAt.value = null
  markDlg.value = true
}
function addMarkRow() { markRows.value.push({ code: '', label: '', enabled: true, connected: false, effectiveFollowUp: false }) }
function delMarkRow(i: number) { markRows.value.splice(i, 1) }
async function saveMarkCodes() {
  for (let i = 0; i < markRows.value.length; i++) {
    if (!markRows.value[i].code) { ElMessage.error('第 ' + (i + 1) + ' 行 code 不能为空'); return }
  }
  if (await saveDomain('MARK_CODES', markRows.value.map((m) => ({
    code: m.code, label: m.label, enabled: m.enabled, connected: m.connected, effectiveFollowUp: m.effectiveFollowUp,
  })), markEffectiveAt.value)) markDlg.value = false
}

// CLOSE_REASONS（数组域：kind=CloseKindEnum / code / label）
const closeDlg = ref(false)
const closeRows = ref<any[]>([])
const closeEffectiveAt = ref<string | null>(null)
const closeKinds: CloseKind[] = ['WITHDRAWN', 'BAD_DEBT']
function openCloseReasons() {
  const arr = domainData('CLOSE_REASONS').closeReasons
  closeRows.value = Array.isArray(arr) ? arr.map((c: any) => ({
    kind: c.kind ?? 'WITHDRAWN', code: c.code ?? '', label: c.label ?? '',
  })) : []
  closeEffectiveAt.value = null
  closeDlg.value = true
}
function addCloseRow() { closeRows.value.push({ kind: 'WITHDRAWN', code: '', label: '' }) }
function delCloseRow(i: number) { closeRows.value.splice(i, 1) }
async function saveCloseReasons() {
  for (let i = 0; i < closeRows.value.length; i++) {
    if (!closeRows.value[i].code) { ElMessage.error('第 ' + (i + 1) + ' 行 code 不能为空'); return }
  }
  if (await saveDomain('CLOSE_REASONS', closeRows.value.map((c) => ({
    kind: c.kind, code: c.code, label: c.label,
  })), closeEffectiveAt.value)) closeDlg.value = false
}

// 权限矩阵 + AI 配置 + 话术库（平台·ai.config 写）
const matrix = ref<any[]>([]); const aiConfig = ref<any>(null); const scripts = ref<any[]>([])
async function loadMore() {
  matrix.value = ((await api.GET('/permission-matrix', {})).data as any) ?? []
  aiConfig.value = (await api.GET('/ai-config', {})).data
  scripts.value = ((await api.GET('/script-lib', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
}
// L-02：权限矩阵客户端 CSV 导出(矩阵已在内存，无需后端端点 BR-M1-04c)。
function csvCell(v: any) {
  const s = v == null ? '' : String(v)
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) return '"' + s.replace(/"/g, '""') + '"'
  return s
}
function exportMatrix() {
  const header = ['功能/模块', '角色', '权限码', '数据范围', '是否允许']
  const lines = [header.join(',')]
  for (let i = 0; i < matrix.value.length; i++) {
    const r = matrix.value[i]
    lines.push([
      csvCell(r.feature), csvCell(r.role), csvCell(r.permission),
      csvCell(r.dataScope), csvCell(r.allowed === false ? '否' : '是'),
    ].join(','))
  }
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = 'permission-matrix.csv'
  document.body.appendChild(a); a.click(); document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已导出权限矩阵 CSV')
}

// AI 配置编辑（PUT /ai-config）
const aiDlg = ref(false)
// 嵌套字段初始就初始化：DsDrawer 是 Teleport 常驻渲染（非 v-if），模板里的 aiForm.llm.provider
// 在抽屉关闭时也会求值；若 aiForm={} 则 aiForm.llm 为 undefined → 整个 /settings 白屏。
const aiForm = ref<any>({ llm: {}, asr: {}, prompts: {}, flywheel: {} })
function openAiEdit() {
  const c = aiConfig.value ?? {}
  // 后端首配可能返回 llm/asr=null，normalize 防 v-model 访问 null 嵌套崩溃
  aiForm.value = { llm: { ...(c.llm ?? {}) }, asr: { ...(c.asr ?? {}) }, prompts: c.prompts ?? {}, flywheel: c.flywheel ?? {} }
  aiDlg.value = true
}
async function saveAi() {
  const { error } = await api.PUT('/ai-config', { body: aiForm.value as any })
  if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('AI 配置已更新'); aiDlg.value = false; loadMore()
}
// ── 三方通道（v1.23.0·GET/PUT /integrations）──
// 用户诉求：「易保全、LLM、ASR 这些三方端口的 key，后台没有配置界面。」
// 只做真接得通的两个：易保全(存证) + 智讯云(短信)。LLM/ASR 的客户端还没写（Phase 3），
// 给它们做输入框只会造出「填了不生效」的空壳页——AI 区域已显式标注「待接入」。
const integrations = ref<any[]>([])
const FIELD_CN: Record<string, string> = {
  baseUrl: '接口地址', smsBaseUrl: '普通短信接口地址', videoBaseUrl: '视频短信接口地址',
  appKey: 'appKey', appKeySecret: 'appKey 密钥', secretName: 'SecretName', secretKey: 'SecretKey',
  apiKey: 'API Key', model: '模型',
}
const SRC_CN: Record<string, string> = { DB: '后台维护', ENV: '环境变量', NONE: '未配置' }
// 每个通道有哪些密钥字段——**不能从 secretsMasked 的键推**：未配置时后端不回该键（null 被剥掉），
// 于是抽屉里一个输入框都没有，全新部署的用户根本填不了 key。这个洞是 E2E 抓出来的。
const SECRET_KEYS: Record<string, string[]> = {
  EBAOQUAN: ['appKey', 'appKeySecret'],
  SMS: ['secretName', 'secretKey'],
  BAILIAN: ['apiKey'],      // v1.24.0 真接入：填了就真去调（录音转写）
  DEEPSEEK: ['apiKey'],     // 填了就真去调（复盘/违规检测/建议）
}
async function loadIntegrations() {
  if (!auth.has('settings.manage')) return
  const { data } = await api.GET('/integrations', {})
  integrations.value = (data as any) ?? []
}
const inDlg = ref(false)
const inForm = ref<any>({ provider: '', name: '', enabled: false, settings: {}, secrets: {}, masked: {}, cryptoReady: true })
function openIntegration(row: any) {
  // 密钥回显的是掩码，绝不能把掩码当明文写回去 → secrets 一律留空，留空即「不改」。
  inForm.value = {
    provider: row.provider, name: row.name, enabled: row.enabled, cryptoReady: row.cryptoReady,
    settings: { ...(row.settings ?? {}) },
    secrets: Object.fromEntries((SECRET_KEYS[row.provider] ?? []).map((k) => [k, ''])),
    masked: row.secretsMasked ?? {},
  }
  inDlg.value = true
}
async function submitIntegration() {
  const f = inForm.value
  // 空串在契约里表示「清除该密钥」；没填的键直接不传，语义才是「不改」。
  const secrets: Record<string, string> = {}
  for (const [k, v] of Object.entries(f.secrets as Record<string, string>)) {
    if (v && String(v).trim()) secrets[k] = String(v).trim()
  }
  const { error } = await api.PUT('/integrations/{provider}', {
    params: { path: { provider: f.provider } },
    body: { enabled: f.enabled, settings: f.settings, secrets } as any,
  })
  if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已保存（改完即刻生效，无需重启）'); inDlg.value = false; loadIntegrations()
}

// 话术库：新建 + 变体晋升
const scDlg = ref(false); const scForm = ref<any>({ scene: '首催开场', intent: '', cohort: '', text: '' })
async function createScript() {
  const { error } = await api.POST('/script-lib', { body: { ...scForm.value } as any })
  if (error) { ElMessage.error('新建话术失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已新建话术（CANDIDATE）'); scDlg.value = false; loadMore()
}
async function promote(s: any) {
  const { error } = await api.POST('/script-lib/{id}/variant/promote', { params: { path: { id: String(s.id) } } } as any)
  if (error) { ElMessage.error('晋升失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('变体已晋升'); loadMore()
}
onMounted(() => { load(); loadMore(); loadIntegrations() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>系统配置 · 业务规则</div>
      <div class="ops"><span class="note" style="margin:0">平台 · 带版本/生效时间 · 变更只对新计时案件生效 BR-M3-19</span></div>
    </div>

    <!-- 人话化（v1.23.0）：此前这里把 JSON 原样 dump 出来（{"holdCap":50}），只有写代码的人看得懂。
         现在每个域一张卡：中文域名 + 这个域管什么 + 每个参数的中文名/当前值/一句话说明。 -->
    <!-- 一域一卡（v1.25.0）：编辑入口就在卡头——此前是页面顶部一排代码名按钮（「编辑时效参数(TIMERS)」），
         和卡片断开，用户找不到「时效参数在哪调」。数组域（标记码/结案原因）此前把整条数组挤成一格
         （closeReasons: E2E_REASON、E2E_REASON…），全是码没有中文名，等于看不懂。 -->
    <div v-for="row in orderedItems" :key="row.domain" class="card" style="margin-bottom:12px;box-shadow:none;border:1px solid var(--bd)">
      <div class="card-h" style="padding-bottom:6px">
        <div class="t" style="font-size:14px">
          {{ DOMAIN_META[row.domain]?.label ?? settingsDomainLabel(row.domain) }}
          <span class="tag inf" style="margin-left:6px" :title="row.domain">v{{ row.version }}</span>
        </div>
        <div class="ops" style="display:flex;align-items:center;gap:10px">
          <span class="note" style="margin:0">生效时间 {{ row.effectiveAt ? String(row.effectiveAt).slice(0,16).replace('T',' ') : '立即' }}</span>
          <button v-if="auth.has('settings.manage')" class="btn sm" @click="editDomain(row.domain)">编辑</button>
        </div>
      </div>
      <div class="note" style="margin:0 0 8px">{{ DOMAIN_META[row.domain]?.desc }}</div>

      <!-- 标记码：码 + 中文名 + 它到底意味着什么（接通/有效跟进会重置无跟进倒计时） -->
      <table v-if="row.domain === 'MARK_CODES'">
        <thead><tr><th style="width:160px">结果码</th><th style="width:180px">名称</th><th style="width:90px">算接通</th><th style="width:110px">算有效跟进</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="m in (row.markCodes ?? [])" :key="m.code">
            <td><code>{{ m.code }}</code></td>
            <td><b>{{ m.label || '（未命名）' }}</b></td>
            <td><span class="tag" :class="m.connected ? 'suc' : 'inf'">{{ m.connected ? '是' : '否' }}</span></td>
            <td><span class="tag" :class="m.effectiveFollowUp ? 'suc' : 'inf'">{{ m.effectiveFollowUp ? '是' : '否' }}</span></td>
            <td class="note" style="margin:0">
              {{ m.effectiveFollowUp ? '选它会重置该案件的「无跟进自动释放」倒计时' : (m.connected ? '算接通，但不重置无跟进倒计时' : '未接通') }}
            </td>
          </tr>
          <tr v-if="!(row.markCodes ?? []).length"><td colspan="5" class="note" style="text-align:center">未配置（催收员标记通话结果时无可选项）</td></tr>
        </tbody>
      </table>

      <!-- 结案原因：按类型分组展示，不再是一串码 -->
      <table v-else-if="row.domain === 'CLOSE_REASONS'">
        <thead><tr><th style="width:160px">类型</th><th style="width:200px">原因码</th><th>名称</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in (row.closeReasons ?? [])" :key="i">
            <td><span class="tag inf">{{ closeKindLabel(c.kind) }}</span></td>
            <td><code>{{ c.code }}</code></td>
            <td><b>{{ c.label || '（未命名）' }}</b></td>
          </tr>
          <tr v-if="!(row.closeReasons ?? []).length"><td colspan="3" class="note" style="text-align:center">未配置（结案时无可选原因）</td></tr>
        </tbody>
      </table>

      <!-- 其余标量域：中文名 / 当前值(带单位) / 一句话说明 -->
      <table v-else>
        <thead><tr><th style="width:170px">参数</th><th style="width:200px">当前值</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="(v, k) in flatFields(row)" :key="k">
            <td><b>{{ fieldLabel(String(k)) }}</b><div class="note" style="margin:0;font-size:11px">{{ k }}</div></td>
            <td>{{ fieldValue(String(k), v) }}</td>
            <td class="note" style="margin:0">{{ fieldDesc(String(k)) || '—' }}</td>
          </tr>
          <tr v-if="!Object.keys(flatFields(row)).length">
            <td colspan="3" class="note" style="text-align:center">未配置（走系统默认值）</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!items.length" class="note" style="text-align:center;padding:24px 0">暂无配置数据（仅平台可见）</div>

    <!-- 权限矩阵：**只读**（权限是代码里的口径，不在这儿改）。它有几百行，摊开会把真正能配的东西全挤到看不见——
         用户原话「不能修改就不要显示，可以修改和配置的才显示」。故默认收起，需要时再展开或导出。 -->
    <div class="sec-title" style="justify-content:space-between">
      <span style="display:flex;align-items:center;gap:8px">
        权限矩阵
        <span class="tag inf">只读参考</span>
        <span style="font-size:12px;color:var(--sec);font-weight:400">功能×角色×权限码×数据范围（{{ matrix.length }} 条）· 权限口径由代码定义，此处不可改</span>
      </span>
      <span style="display:flex;gap:8px">
        <button class="btn txt" @click="matrixOpen = !matrixOpen">{{ matrixOpen ? '收起' : '展开查看' }}</button>
        <button v-if="auth.has('settings.manage')" class="btn txt" @click="exportMatrix">导出 CSV</button>
      </span>
    </div>
    <table v-if="matrixOpen">
      <thead>
        <tr><th>功能/模块</th><th style="width:80px">角色</th><th>权限码</th><th>数据范围</th></tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in matrix" :key="i">
          <td>{{ row.feature }}</td>
          <td :title="row.role">{{ roleTemplateLabel(row.role) }}</td>
          <td :title="row.permission">{{ permLabel(row.permission) }}</td>
          <td :title="row.dataScope">{{ dataScopeLabel(row.dataScope) }}</td>
        </tr>
        <tr v-if="!matrix.length"><td colspan="4" class="note" style="text-align:center">暂无权限矩阵</td></tr>
      </tbody>
    </table>

    <!-- 三方通道（v1.23.0）：密钥加密落库，读接口只回掩码 -->
    <template v-if="auth.has('settings.manage')">
      <div class="sec-title">
        三方通道
        <span style="font-size:12px;color:var(--sec);font-weight:400">密钥加密存储，页面只回显后四位；改完即刻生效，无需重启</span>
      </div>
      <table>
        <thead>
          <tr>
            <th style="width:190px">通道</th>
            <th style="width:100px">状态</th>
            <th style="width:100px">配置来源</th>
            <th>接口地址 / 密钥</th>
            <th style="width:140px">最近修改</th>
            <th style="width:70px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ig in integrations" :key="ig.provider">
            <td><b>{{ ig.name }}</b><div class="note" style="margin:0;font-size:11px">{{ ig.provider }}</div></td>
            <td>
              <span class="tag" :class="ig.enabled ? (ig.configured ? 'suc' : 'dan') : 'inf'">
                {{ ig.enabled ? (ig.configured ? '已启用' : '启用但未填齐') : '未启用' }}
              </span>
            </td>
            <td><span class="tag" :class="ig.source === 'NONE' ? 'war' : 'inf'">{{ SRC_CN[ig.source] ?? ig.source }}</span></td>
            <td>
              <div v-for="(v, k) in (ig.settings ?? {})" :key="k" class="note" style="margin:0">
                {{ FIELD_CN[String(k)] ?? k }}：{{ v || '—' }}
              </div>
              <div v-for="(v, k) in (ig.secretsMasked ?? {})" :key="'s'+k" class="note" style="margin:0">
                {{ FIELD_CN[String(k)] ?? k }}：<code>{{ v || '未配置' }}</code>
              </div>
            </td>
            <td class="note" style="margin:0">
              <span v-if="ig.updatedAt">{{ String(ig.updatedAt).slice(0,16).replace('T',' ') }} {{ ig.updatedByName || '' }}</span>
              <span v-else>—</span>
            </td>
            <td><button class="btn txt" @click="openIntegration(ig)">配置</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="integrations.length && !integrations[0].cryptoReady" class="alert warn">
        <span>未配置主密钥 <b>HUICUI_CRYPTO_KEY</b>：无法在后台保存密钥（密钥必须加密落库）。请在部署环境注入后重启，或继续用环境变量配置三方 key。</span>
      </div>
      <div class="alert info">
        <span>
          填入百炼 / DeepSeek 的 API Key 并启用后<b>立即生效</b>（无需重启）：新上传的录音会真的送去转写，
          转写完成后自动出 AI 复盘小结与违规检测，检出的违规直接进【质检/风控】的复核流程。
          <b>未配置时维持占位行为</b>——录音停在「解析中」，不会报错、不影响催收员上传。
        </span>
      </div>
    </template>

    <div class="sec-title" style="justify-content:space-between">
      <span style="display:flex;align-items:center;gap:8px">AI 配置（GET/PUT /ai-config · 话术飞轮 LLM/ASR）</span>
      <button v-if="auth.has('ai.config')" class="btn txt" @click="openAiEdit">编辑</button>
    </div>
    <div class="alert" :class="aiLive ? 'info' : 'warn'" style="margin-top:0">
      <span v-if="aiLive">
        <b>AI 已接入并生效</b>：录音转写走百炼、复盘与违规检测走 DeepSeek。下面的模型/温度/提示词
        <b>会真的影响输出</b>——改提示词等于改 AI 的判断口径，请谨慎。
      </span>
      <span v-else>
        <b>AI 引擎已实现，但尚未配置密钥</b>——请在上方【三方通道】填入百炼 / DeepSeek 的 API Key 并启用。
        未配置时：录音停在「解析中」、不出复盘（不报错）。下面的模型/温度/提示词要等密钥配好后才会真正生效。
      </span>
    </div>
    <div v-if="aiConfig">
      <table v-for="grp in AI_GROUPS" :key="grp.key" style="margin-bottom:10px">
        <thead>
          <tr>
            <th style="width:170px">
              {{ grp.label }}
              <span class="tag" :class="grp.live ? 'suc' : 'war'" style="margin-left:6px">{{ grp.live ? '已生效' : '待接入' }}</span>
            </th>
            <th style="width:220px">当前值</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(v, k) in ((aiConfig as any)[grp.key] ?? {})" :key="k">
            <td><b>{{ fieldLabel(String(k)) }}</b><div class="note" style="margin:0;font-size:11px">{{ k }}</div></td>
            <td>{{ fieldValue(String(k), v) }}</td>
            <td class="note" style="margin:0">{{ fieldDesc(String(k)) || '—' }}</td>
          </tr>
          <tr v-if="!Object.keys((aiConfig as any)[grp.key] ?? {}).length">
            <td colspan="3" class="note" style="text-align:center">未配置</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="note">暂无 AI 配置</div>

    <div class="sec-title" style="justify-content:space-between">
      <span style="display:flex;align-items:center;gap:8px">话术库（GET /script-lib · 飞轮护城河）</span>
      <button v-if="auth.has('ai.config')" class="btn txt" @click="scDlg=true">+ 新建话术</button>
    </div>
    <table>
      <thead>
        <tr><th>场景</th><th>意图</th><th style="width:90px">来源</th><th>效果</th><th style="width:100px">状态</th><th style="width:90px">操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in scripts" :key="row.id">
          <td>{{ row.scene }}</td>
          <td>{{ row.intent }}</td>
          <td>{{ scriptSourceLabel(row.source) }}</td>
          <td class="num">承诺 {{ ((row.promiseRate??0)*100).toFixed(0) }}% / 回款 {{ ((row.repayRate??0)*100).toFixed(0) }}%</td>
          <td><span class="tag" :class="row.status==='EFFECTIVE'?'suc':row.status==='RETIRED'?'inf':'war'">{{ scriptStatusLabel(row.status) }}</span></td>
          <td><button v-if="auth.has('ai.config') && row.variant" class="btn txt" @click="promote(row)">变体晋升</button></td>
        </tr>
        <tr v-if="!scripts.length"><td colspan="6" class="note" style="text-align:center">暂无话术</td></tr>
      </tbody>
    </table>

    <DsDrawer v-model="aiDlg" title="编辑 AI 配置" :width="460">
      <el-form label-width="110px">
        <el-form-item label="LLM provider"><el-input v-model="aiForm.llm.provider" /></el-form-item>
        <el-form-item label="LLM model"><el-input v-model="aiForm.llm.model" /></el-form-item>
        <el-form-item label="temperature"><el-input-number v-model="aiForm.llm.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
        <el-form-item label="ASR provider"><el-input v-model="aiForm.asr.provider" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="aiDlg=false">取消</el-button><el-button type="primary" @click="saveAi">保存</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="inDlg" :title="`配置 · ${inForm.name}`" :width="520">
      <div class="alert info" style="margin-top:0;margin-bottom:10px">
        <span>密钥<b>加密存储</b>，页面只回显后四位。<b>留空 = 保持不变</b>；改完即刻生效，无需重启服务。</span>
      </div>
      <el-form label-width="150px">
        <el-form-item label="启用该通道">
          <el-switch v-model="inForm.enabled" />
          <div class="note" style="margin:0;font-size:11px">未启用时：存证只落占位记录、短信不会真发出去。启用前必须填齐密钥与接口地址，否则保存会被拒绝。</div>
        </el-form-item>
        <el-form-item v-for="(v, k) in inForm.settings" :key="k" :label="FIELD_CN[String(k)] ?? String(k)">
          <el-input v-model="inForm.settings[k]" />
        </el-form-item>
        <el-form-item v-for="(v, k) in inForm.secrets" :key="'s'+k" :label="FIELD_CN[String(k)] ?? String(k)">
          <el-input v-model="inForm.secrets[k]" type="password" show-password
                    :placeholder="inForm.masked?.[k] ? ('当前 ' + inForm.masked[k] + '（留空=不改）') : '未配置'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inDlg=false">取消</el-button>
        <el-button type="primary" :disabled="!inForm.cryptoReady" @click="submitIntegration">保存</el-button>
      </template>
    </DsDrawer>

    <DsDrawer v-model="scDlg" title="新建话术" :width="440">
      <el-form label-width="80px">
        <el-form-item label="场景"><el-input v-model="scForm.scene" /></el-form-item>
        <el-form-item label="意图"><el-input v-model="scForm.intent" /></el-form-item>
        <el-form-item label="人群"><el-input v-model="scForm.cohort" /></el-form-item>
        <el-form-item label="话术"><el-input v-model="scForm.text" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="scDlg=false">取消</el-button><el-button type="primary" @click="createScript">新建</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="rotDlg" title="编辑轮转配置" :width="400">
      <el-form label-width="120px">
        <el-form-item label="持有上限 holdCap"><el-input-number v-model="rotForm.holdCap" :min="1" /></el-form-item>
        <el-form-item label="最大轮转 maxRotations"><el-input-number v-model="rotForm.maxRotations" :min="0" /></el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker v-model="rotForm.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=立即生效" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="rotDlg=false">取消</el-button><el-button type="primary" @click="saveRotation">保存新版本</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="timerDlg" title="编辑时效参数" :width="460">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="建议值：T1=48h(派单时限) / T2=168h(服务商处置) / TC=168h(无跟进释放) / MAXCYCLE=90天。变更仅对新计时案件生效。" />
      <el-form label-width="180px">
        <el-form-item label="T1 派单时限(小时)"><el-input-number v-model="timerForm.t1Hours" :min="0" /></el-form-item>
        <el-form-item label="T2 服务商处置(小时)"><el-input-number v-model="timerForm.t2Hours" :min="0" /></el-form-item>
        <el-form-item label="TC 无跟进释放(小时)"><el-input-number v-model="timerForm.tCollectorHours" :min="0" /></el-form-item>
        <el-form-item label="最长周期(天)"><el-input-number v-model="timerForm.maxCycleDays" :min="0" /></el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker v-model="timerForm.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=立即生效" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="timerDlg=false">取消</el-button><el-button type="primary" @click="saveTimers">保存新版本</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="markDlg" title="编辑标记码" :width="720">
      <div style="margin-bottom:10px;display:flex;align-items:center;gap:8px">
        <span style="color:#606266;font-size:13px">生效时间</span>
        <el-date-picker v-model="markEffectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=立即生效" style="width:240px" />
      </div>
      <el-button size="small" type="primary" plain style="margin-bottom:10px" @click="addMarkRow">+ 新增标记码</el-button>
      <el-table :data="markRows" border size="small">
        <el-table-column label="code" width="140"><template #default="{row}"><el-input v-model="row.code" size="small" /></template></el-table-column>
        <el-table-column label="label"><template #default="{row}"><el-input v-model="row.label" size="small" /></template></el-table-column>
        <el-table-column label="启用" width="70"><template #default="{row}"><el-switch v-model="row.enabled" /></template></el-table-column>
        <el-table-column label="接通" width="70"><template #default="{row}"><el-switch v-model="row.connected" /></template></el-table-column>
        <el-table-column label="有效跟进" width="90"><template #default="{row}"><el-switch v-model="row.effectiveFollowUp" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{$index}"><el-button size="small" text type="danger" @click="delMarkRow($index)">删除</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="markDlg=false">取消</el-button><el-button type="primary" @click="saveMarkCodes">保存新版本</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="closeDlg" title="编辑结案原因" :width="640">
      <div style="margin-bottom:10px;display:flex;align-items:center;gap:8px">
        <span style="color:#606266;font-size:13px">生效时间</span>
        <el-date-picker v-model="closeEffectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=立即生效" style="width:240px" />
      </div>
      <el-button size="small" type="primary" plain style="margin-bottom:10px" @click="addCloseRow">+ 新增结案原因</el-button>
      <el-table :data="closeRows" border size="small">
        <el-table-column label="类型 kind" width="170"><template #default="{row}">
          <el-select v-model="row.kind" size="small">
            <el-option v-for="k in closeKinds" :key="k" :label="k" :value="k" />
          </el-select>
        </template></el-table-column>
        <el-table-column label="code" width="180"><template #default="{row}"><el-input v-model="row.code" size="small" /></template></el-table-column>
        <el-table-column label="label"><template #default="{row}"><el-input v-model="row.label" size="small" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{$index}"><el-button size="small" text type="danger" @click="delCloseRow($index)">删除</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="closeDlg=false">取消</el-button><el-button type="primary" @click="saveCloseReasons">保存新版本</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="smsDlg" title="编辑短信配置" :width="600">
      <el-form label-width="160px">
        <el-form-item label="同案冷却(分钟)"><el-input-number v-model="smsForm.cooldownMinutes" :min="0" /></el-form-item>
        <el-form-item label="条数预警阈值"><el-input-number v-model="smsForm.warnThreshold" :min="0" /></el-form-item>
        <el-form-item label="短信签名"><el-input v-model="smsForm.signature" placeholder="平台统一配置 BR-M9-09" /></el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker v-model="smsForm.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=立即生效" style="width:100%" />
        </el-form-item>
      </el-form>
      <!-- v1.21.0：短信模板已由「短信通道」按组织接管（org_sms_template + 平台代报备流程）。
           此处仅保留平台默认值（组织未单独配置时的兜底）。原来的模板编辑表格是「只写不读」的——
           后端从不读 settings.sms.templates，填了也不会生效，故移除以免误导。 -->
      <div class="alert info" style="margin-top:10px">
        <span>以上为<b>平台默认值</b>（组织未单独配置时兜底，同时用于登录验证码短信）。各物业的签名与模板请在
          <a class="link" @click="$router.push('/sms')">【短信通道】</a>按组织配置——那里的模板走「平台代向运营商报备 → 回填模板ID → 生效」流程。</span>
      </div>
      <template #footer><el-button @click="smsDlg=false">取消</el-button><el-button type="primary" @click="saveSms">保存新版本</el-button></template>
    </DsDrawer>
  </div>
</template>
