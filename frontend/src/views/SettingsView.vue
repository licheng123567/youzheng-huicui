<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import type { components } from '../api/schema'

type SettingsInput = components['schemas']['SettingsInput']
type CloseKind = components['schemas']['CloseKindEnum']

// 系统配置(平台)：GET /settings 列业务规则各域(带版本/生效时间，参数变更只对新计时案件生效 BR-M3-19)。
// M-09：全 5 域可编辑(TIMERS/ROTATION/MARK_CODES/CLOSE_REASONS/SMS)，复用通用 saveDomain。
const auth = useAuth()
const items = ref<any[]>([])
const fmt = (v: any) => v == null ? '—' : JSON.stringify(v)
const domainOf = (x: any) => x.timers ?? x.rotation ?? x.markCodes ?? x.closeReasons ?? x.sms

async function load() {
  const { data, error } = await api.GET('/settings', {})
  if (error) { ElMessage.error('加载失败（仅平台可见）'); return }
  items.value = (data as any) ?? []
}
function domainData(domain: string) {
  const row = items.value.find((x) => x.domain === domain)
  return row ?? {}
}
// 通用保存：PUT /settings body={domain, <域字段>:value}，写新版本(对新计时案件生效 BR-M3-19)。
async function saveDomain(domain: SettingsInput['domain'], value: any) {
  const body: any = { domain }
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
const rotForm = ref<any>({ holdCap: 50, maxRotations: 3 })
function openRotation() {
  const rot = domainData('ROTATION').rotation ?? {}
  rotForm.value = { holdCap: rot.holdCap ?? 50, maxRotations: rot.maxRotations ?? 3 }
  rotDlg.value = true
}
async function saveRotation() {
  if (rotForm.value.holdCap < 1 || rotForm.value.maxRotations < 0) { ElMessage.error('请填写非负合理值'); return }
  if (await saveDomain('ROTATION', { holdCap: rotForm.value.holdCap, maxRotations: rotForm.value.maxRotations })) rotDlg.value = false
}

// TIMERS（CFG-TIMERS-DRAFT：T1=48h / T2=168h / TC=168h / MAXCYCLE=90天，非法/负数拒绝 US-M3-11）
const timerDlg = ref(false)
const timerForm = ref<any>({ t1Hours: 48, t2Hours: 168, tCollectorHours: 168, maxCycleDays: 90 })
function openTimers() {
  const t = domainData('TIMERS').timers ?? {}
  timerForm.value = {
    t1Hours: t.t1Hours ?? 48,
    t2Hours: t.t2Hours ?? 168,
    tCollectorHours: t.tCollectorHours ?? 168,
    maxCycleDays: t.maxCycleDays ?? 90,
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
  })) timerDlg.value = false
}

// SMS（cooldownMinutes 物业可见；signature/templates/warnThreshold 平台统一配置 BR-M9-09）
const smsDlg = ref(false)
const smsForm = ref<any>({ cooldownMinutes: 60, signature: '', warnThreshold: null, templates: [] })
function openSms() {
  const s = domainData('SMS').sms ?? {}
  smsForm.value = {
    cooldownMinutes: s.cooldownMinutes ?? 60,
    signature: s.signature ?? '',
    warnThreshold: s.warnThreshold ?? null,
    templates: Array.isArray(s.templates) ? s.templates.map((t: any) => ({ ...t })) : [],
  }
  smsDlg.value = true
}
function addSmsTpl() { smsForm.value.templates.push({ id: '', name: '', content: '' }) }
function delSmsTpl(i: number) { smsForm.value.templates.splice(i, 1) }
async function saveSms() {
  const f = smsForm.value
  if (f.cooldownMinutes < 0) { ElMessage.error('冷却分钟不能为负'); return }
  if (f.warnThreshold != null && f.warnThreshold < 0) { ElMessage.error('预警阈值不能为负'); return }
  if (await saveDomain('SMS', {
    cooldownMinutes: f.cooldownMinutes,
    signature: f.signature || null,
    warnThreshold: f.warnThreshold,
    templates: f.templates,
  })) smsDlg.value = false
}

// MARK_CODES（数组域，connected/effectiveFollowUp 结构须与读一致 BR-M4-12）
const markDlg = ref(false)
const markRows = ref<any[]>([])
function openMarkCodes() {
  const arr = domainData('MARK_CODES').markCodes
  markRows.value = Array.isArray(arr) ? arr.map((m: any) => ({
    code: m.code ?? '', label: m.label ?? '', enabled: m.enabled !== false,
    connected: m.connected === true, effectiveFollowUp: m.effectiveFollowUp === true,
  })) : []
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
  })))) markDlg.value = false
}

// CLOSE_REASONS（数组域：kind=CloseKindEnum / code / label）
const closeDlg = ref(false)
const closeRows = ref<any[]>([])
const closeKinds: CloseKind[] = ['WITHDRAWN', 'BAD_DEBT']
function openCloseReasons() {
  const arr = domainData('CLOSE_REASONS').closeReasons
  closeRows.value = Array.isArray(arr) ? arr.map((c: any) => ({
    kind: c.kind ?? 'WITHDRAWN', code: c.code ?? '', label: c.label ?? '',
  })) : []
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
  })))) closeDlg.value = false
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
const aiDlg = ref(false); const aiForm = ref<any>({})
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
onMounted(() => { load(); loadMore() })
</script>

<template>
  <el-card header="系统配置 · 业务规则（平台 · 带版本/生效时间 · 变更只对新计时案件生效 BR-M3-19）">
    <div v-if="auth.has('settings.manage')" style="margin-bottom:10px">
      <el-button type="primary" size="small" @click="openTimers">编辑时效参数(TIMERS)</el-button>
      <el-button type="primary" size="small" @click="openRotation">编辑轮转配置(ROTATION)</el-button>
      <el-button type="primary" size="small" @click="openMarkCodes">编辑标记码(MARK_CODES)</el-button>
      <el-button type="primary" size="small" @click="openCloseReasons">编辑结案原因(CLOSE_REASONS)</el-button>
      <el-button type="primary" size="small" @click="openSms">编辑短信配置(SMS)</el-button>
    </div>
    <el-table :data="items" border size="small">
      <el-table-column prop="domain" label="配置域" width="140" />
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="effectiveAt" label="生效时间" width="200" />
      <el-table-column label="配置内容"><template #default="{row}"><code style="font-size:12px">{{ fmt(domainOf(row)) }}</code></template></el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="域：TIMERS(计时器) / ROTATION(轮转·持有上限) / MARK_CODES(标记码) / CLOSE_REASONS(结案原因) / SMS。" />

    <el-divider content-position="left">权限矩阵（GET /permission-matrix · 功能×角色×权限码×数据范围 BR-M1-04c）
      <el-button v-if="auth.has('settings.manage')" size="small" text type="primary" @click="exportMatrix">导出 CSV</el-button></el-divider>
    <el-table :data="matrix" border size="small" max-height="280">
      <el-table-column prop="feature" label="功能/模块" /><el-table-column prop="role" label="角色" width="80" />
      <el-table-column prop="permission" label="权限码" /><el-table-column prop="dataScope" label="数据范围" />
    </el-table>

    <el-divider content-position="left">AI 配置（GET/PUT /ai-config · 话术飞轮 LLM/ASR）<el-button v-if="auth.has('ai.config')" size="small" text type="primary" @click="openAiEdit">编辑</el-button></el-divider>
    <el-descriptions v-if="aiConfig" :column="2" border size="small">
      <el-descriptions-item label="LLM"><code>{{ fmt(aiConfig.llm) }}</code></el-descriptions-item>
      <el-descriptions-item label="ASR"><code>{{ fmt(aiConfig.asr) }}</code></el-descriptions-item>
      <el-descriptions-item label="Prompts"><code style="font-size:11px">{{ fmt(aiConfig.prompts) }}</code></el-descriptions-item>
      <el-descriptions-item label="飞轮"><code>{{ fmt(aiConfig.flywheel) }}</code></el-descriptions-item>
    </el-descriptions>

    <el-divider content-position="left">话术库（GET /script-lib · 飞轮护城河）<el-button v-if="auth.has('ai.config')" size="small" text type="primary" @click="scDlg=true">+ 新建话术</el-button></el-divider>
    <el-table :data="scripts" border size="small" max-height="280">
      <el-table-column prop="scene" label="场景" /><el-table-column prop="intent" label="意图" />
      <el-table-column prop="source" label="来源" width="90" />
      <el-table-column label="效果"><template #default="{row}">承诺 {{ ((row.promiseRate??0)*100).toFixed(0) }}% / 回款 {{ ((row.repayRate??0)*100).toFixed(0) }}%</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag size="small" :type="row.status==='EFFECTIVE'?'success':row.status==='RETIRED'?'info':'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="auth.has('ai.config') && row.variant" size="small" @click="promote(row)">变体晋升</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="aiDlg" title="编辑 AI 配置（PUT /ai-config · ai.config）" width="460px">
      <el-form label-width="110px">
        <el-form-item label="LLM provider"><el-input v-model="aiForm.llm.provider" /></el-form-item>
        <el-form-item label="LLM model"><el-input v-model="aiForm.llm.model" /></el-form-item>
        <el-form-item label="temperature"><el-input-number v-model="aiForm.llm.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
        <el-form-item label="ASR provider"><el-input v-model="aiForm.asr.provider" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="aiDlg=false">取消</el-button><el-button type="primary" @click="saveAi">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="scDlg" title="新建话术（POST /script-lib · ai.config）" width="440px">
      <el-form label-width="80px">
        <el-form-item label="场景"><el-input v-model="scForm.scene" /></el-form-item>
        <el-form-item label="意图"><el-input v-model="scForm.intent" /></el-form-item>
        <el-form-item label="人群"><el-input v-model="scForm.cohort" /></el-form-item>
        <el-form-item label="话术"><el-input v-model="scForm.text" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="scDlg=false">取消</el-button><el-button type="primary" @click="createScript">新建</el-button></template>
    </el-dialog>

    <el-dialog v-model="rotDlg" title="编辑轮转配置 ROTATION（PUT /settings·写新版本）" width="400px">
      <el-form label-width="120px">
        <el-form-item label="持有上限 holdCap"><el-input-number v-model="rotForm.holdCap" :min="1" /></el-form-item>
        <el-form-item label="最大轮转 maxRotations"><el-input-number v-model="rotForm.maxRotations" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="rotDlg=false">取消</el-button><el-button type="primary" @click="saveRotation">保存新版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="timerDlg" title="编辑时效参数 TIMERS（PUT /settings·只对新计时案件生效 BR-M3-19）" width="460px">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="建议值：T1=48h(派单时限) / T2=168h(服务商处置) / TC=168h(无跟进释放) / MAXCYCLE=90天。变更仅对新计时案件生效。" />
      <el-form label-width="180px">
        <el-form-item label="T1 派单时限(小时)"><el-input-number v-model="timerForm.t1Hours" :min="0" /></el-form-item>
        <el-form-item label="T2 服务商处置(小时)"><el-input-number v-model="timerForm.t2Hours" :min="0" /></el-form-item>
        <el-form-item label="TC 无跟进释放(小时)"><el-input-number v-model="timerForm.tCollectorHours" :min="0" /></el-form-item>
        <el-form-item label="最长周期(天)"><el-input-number v-model="timerForm.maxCycleDays" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="timerDlg=false">取消</el-button><el-button type="primary" @click="saveTimers">保存新版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="markDlg" title="编辑标记码 MARK_CODES（connected/effectiveFollowUp 影响 T_collector 重置 BR-M4-12）" width="720px">
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
    </el-dialog>

    <el-dialog v-model="closeDlg" title="编辑结案原因 CLOSE_REASONS（PUT /settings·写新版本）" width="640px">
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
    </el-dialog>

    <el-dialog v-model="smsDlg" title="编辑短信配置 SMS（签名/模板由平台统一配置 BR-M9-09，物业不创建）" width="600px">
      <el-form label-width="160px">
        <el-form-item label="同案冷却(分钟)"><el-input-number v-model="smsForm.cooldownMinutes" :min="0" /></el-form-item>
        <el-form-item label="条数预警阈值"><el-input-number v-model="smsForm.warnThreshold" :min="0" /></el-form-item>
        <el-form-item label="短信签名"><el-input v-model="smsForm.signature" placeholder="平台统一配置 BR-M9-09" /></el-form-item>
      </el-form>
      <el-divider content-position="left">短信模板（平台统一配置 BR-M9-09）<el-button size="small" type="primary" plain @click="addSmsTpl">+ 新增模板</el-button></el-divider>
      <el-table :data="smsForm.templates" border size="small">
        <el-table-column label="id" width="120"><template #default="{row}"><el-input v-model="row.id" size="small" /></template></el-table-column>
        <el-table-column label="名称" width="140"><template #default="{row}"><el-input v-model="row.name" size="small" /></template></el-table-column>
        <el-table-column label="内容"><template #default="{row}"><el-input v-model="row.content" size="small" type="textarea" :rows="2" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{$index}"><el-button size="small" text type="danger" @click="delSmsTpl($index)">删除</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="smsDlg=false">取消</el-button><el-button type="primary" @click="saveSms">保存新版本</el-button></template>
    </el-dialog>
  </el-card>
</template>
