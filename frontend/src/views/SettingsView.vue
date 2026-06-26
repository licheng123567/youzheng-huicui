<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 系统配置(平台)：GET /settings 列业务规则各域(带版本/生效时间，参数变更只对新计时案件生效 BR-M3-19)。
// 演示：可编辑 ROTATION.holdCap(催收员持有上限)，PUT 写新版本。
const auth = useAuth()
const items = ref<any[]>([])
const dlg = ref(false)
const form = ref<any>({ holdCap: 50, maxRotations: 3 })

async function load() {
  const { data, error } = await api.GET('/settings', {})
  if (error) { ElMessage.error('加载失败（仅平台可见）'); return }
  items.value = (data as any) ?? []
}
function openRotation() {
  const rot = items.value.find((x) => x.domain === 'ROTATION')?.rotation ?? {}
  form.value = { holdCap: rot.holdCap ?? 50, maxRotations: rot.maxRotations ?? 3 }
  dlg.value = true
}
async function saveRotation() {
  const { error } = await api.PUT('/settings', { body: { domain: 'ROTATION', rotation: { holdCap: form.value.holdCap, maxRotations: form.value.maxRotations } } as any })
  if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已保存新版本（对新计时案件生效）'); dlg.value = false; load()
}
const fmt = (v: any) => v == null ? '—' : JSON.stringify(v)
const domainOf = (x: any) => x.timers ?? x.rotation ?? x.markCodes ?? x.closeReasons ?? x.sms
// 权限矩阵 + AI 配置 + 话术库（平台·ai.config 写）
const matrix = ref<any[]>([]); const aiConfig = ref<any>(null); const scripts = ref<any[]>([])
async function loadMore() {
  matrix.value = ((await api.GET('/permission-matrix', {})).data as any) ?? []
  aiConfig.value = (await api.GET('/ai-config', {})).data
  scripts.value = ((await api.GET('/script-lib', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
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
    <el-button v-if="auth.has('settings.manage')" type="primary" size="small" style="margin-bottom:10px" @click="openRotation">编辑轮转配置(ROTATION)</el-button>
    <el-table :data="items" border size="small">
      <el-table-column prop="domain" label="配置域" width="140" />
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="effectiveAt" label="生效时间" width="200" />
      <el-table-column label="配置内容"><template #default="{row}"><code style="font-size:12px">{{ fmt(domainOf(row)) }}</code></template></el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="域：TIMERS(计时器) / ROTATION(轮转·持有上限) / MARK_CODES(标记码) / CLOSE_REASONS(结案原因) / SMS。" />

    <el-divider content-position="left">权限矩阵（GET /permission-matrix · 功能×角色×权限码×数据范围 BR-M1-04c）</el-divider>
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

    <el-dialog v-model="dlg" title="编辑轮转配置 ROTATION（PUT /settings·写新版本）" width="400px">
      <el-form label-width="120px">
        <el-form-item label="持有上限 holdCap"><el-input-number v-model="form.holdCap" :min="1" /></el-form-item>
        <el-form-item label="最大轮转 maxRotations"><el-input-number v-model="form.maxRotations" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="saveRotation">保存新版本</el-button></template>
    </el-dialog>
  </el-card>
</template>
