<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// GET /projects/{id} → oneOf(Project|ProjectForProvider)；viewRole 判别。+ 作战手册采纳(US-M5-07)。
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const pid = String(route.params.id)
const p = ref<any>(null)
const playbook = ref<any>(null)

async function load() {
  const { data, error } = await api.GET('/projects/{id}', { params: { path: { id: pid } } })
  if (error || !data) { ElMessage.error('加载失败'); return }
  p.value = data
  playbook.value = (await api.GET('/projects/{id}/playbook', { params: { path: { id: pid } } })).data
}
// 作战手册采纳（AI 产草稿→物业采纳才发布·playbook.adopt）
const dlg = ref(false); const form = ref<any>({ version: '', content: '' })
function openAdopt() { form.value = { version: playbook.value?.version ?? 'v1.0', content: playbook.value?.content ?? '' }; dlg.value = true }
async function adopt() {
  const { error } = await api.POST('/projects/{id}/playbook', { params: { path: { id: pid } }, body: { version: form.value.version, content: form.value.content } as any })
  if (error) { ElMessage.error('采纳失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已采纳/发布作战手册'); dlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <el-card v-if="p">
    <template #header>
      <el-button link @click="router.back()">← 返回</el-button>
      <span style="margin-left:8px">项目详情：{{ p.name }}（视角 {{ p.viewRole }}）</span>
    </template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="ID">{{ p.id }}</el-descriptions-item>
      <el-descriptions-item label="区域">{{ p.area }}</el-descriptions-item>
      <el-descriptions-item label="物业公司">{{ p.propCompany ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="合同类型">{{ p.contractType ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="收佣比例">
        {{ p.commInRate != null ? (p.commInRate * 100).toFixed(2) + '%' : '— 服务商视角字段级不可见（资金双线隔离）' }}
      </el-descriptions-item>
      <el-descriptions-item label="状态">{{ p.status }}</el-descriptions-item>
    </el-descriptions>

    <el-divider content-position="left">作战手册（GET /projects/{id}/playbook · AI 产草稿→物业采纳才发布 US-M5-07）
      <el-button v-if="auth.has('playbook.adopt')" size="small" text type="primary" @click="openAdopt">采纳/编辑</el-button>
    </el-divider>
    <el-descriptions v-if="playbook" :column="2" border size="small">
      <el-descriptions-item label="版本">{{ playbook.version ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="采纳模式">{{ playbook.adoptMode ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="内容" :span="2"><div style="white-space:pre-wrap;max-height:160px;overflow:auto">{{ playbook.content ?? '（尚无手册，点采纳发布）' }}</div></el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="尚无作战手册" :image-size="50" />

    <el-dialog v-model="dlg" title="采纳作战手册（POST /projects/{id}/playbook · playbook.adopt）" width="560px">
      <el-form label-width="70px">
        <el-form-item label="版本"><el-input v-model="form.version" placeholder="如 v1.1" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="8" placeholder="作战手册正文（通话前策略/话术指引）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="adopt">采纳发布</el-button></template>
    </el-dialog>
  </el-card>
</template>
