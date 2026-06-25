<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// GET /cases/{id} → CaseDetail{case,contacts,timeline,projectRef,...}（聚合端点，一次取齐）。
const route = useRoute()
const router = useRouter()
const d = ref<any>(null)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

onMounted(async () => {
  const { data, error } = await api.GET('/cases/{id}', { params: { path: { id: String(route.params.id) } } })
  if (error || !data) { ElMessage.error('加载失败'); return }
  d.value = data
})
</script>

<template>
  <el-card v-if="d">
    <template #header>
      <el-button link @click="router.back()">← 返回</el-button>
      <span style="margin-left:8px">案件详情：{{ d.case?.ownerName }} {{ d.case?.room }}</span>
    </template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="户号">{{ d.case?.acctNo }}</el-descriptions-item>
      <el-descriptions-item label="项目">{{ d.case?.projectName }}</el-descriptions-item>
      <el-descriptions-item label="应收">{{ yuan(d.case?.dueCents) }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ d.case?.status }}</el-descriptions-item>
      <el-descriptions-item label="法务阶段">{{ d.case?.legalStage }}</el-descriptions-item>
      <el-descriptions-item label="池">{{ d.case?.pool }}</el-descriptions-item>
    </el-descriptions>
    <el-divider>联系方式</el-divider>
    <el-table :data="d.contacts ?? []" border size="small">
      <el-table-column prop="phone" label="电话" />
      <el-table-column prop="label" label="标签" />
    </el-table>
    <el-empty v-if="!(d.contacts && d.contacts.length)" description="暂无联系方式" :image-size="60" />
  </el-card>
</template>
