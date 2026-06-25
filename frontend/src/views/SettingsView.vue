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
// 权限矩阵 + AI 配置（平台）
const matrix = ref<any[]>([]); const aiConfig = ref<any>(null)
async function loadMore() {
  matrix.value = ((await api.GET('/permission-matrix', {})).data as any) ?? []
  aiConfig.value = (await api.GET('/ai-config', {})).data
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

    <el-divider content-position="left">AI 配置（GET /ai-config · 话术飞轮 LLM/ASR/Prompt）</el-divider>
    <el-descriptions v-if="aiConfig" :column="2" border size="small">
      <el-descriptions-item label="LLM"><code>{{ fmt(aiConfig.llm) }}</code></el-descriptions-item>
      <el-descriptions-item label="ASR"><code>{{ fmt(aiConfig.asr) }}</code></el-descriptions-item>
      <el-descriptions-item label="Prompts"><code style="font-size:11px">{{ fmt(aiConfig.prompts) }}</code></el-descriptions-item>
      <el-descriptions-item label="飞轮"><code>{{ fmt(aiConfig.flywheel) }}</code></el-descriptions-item>
    </el-descriptions>

    <el-dialog v-model="dlg" title="编辑轮转配置 ROTATION（PUT /settings·写新版本）" width="400px">
      <el-form label-width="120px">
        <el-form-item label="持有上限 holdCap"><el-input-number v-model="form.holdCap" :min="1" /></el-form-item>
        <el-form-item label="最大轮转 maxRotations"><el-input-number v-model="form.maxRotations" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="saveRotation">保存新版本</el-button></template>
    </el-dialog>
  </el-card>
</template>
