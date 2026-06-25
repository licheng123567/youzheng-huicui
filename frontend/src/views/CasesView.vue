<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// GET /cases（契约客户端）。金额 *_cents 分→元展示。
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

onMounted(async () => {
  loading.value = true
  const { data, error } = await api.GET('/cases', { params: { query: { page: 1, size: 20 } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
})
</script>

<template>
  <el-card :header="`案件（GET /cases · 共 ${total}）`">
    <el-table v-loading="loading" :data="items" border @row-click="(r:any)=>router.push(`/cases/${r.id}`)" style="cursor:pointer">
      <el-table-column prop="acctNo" label="户号" width="100" />
      <el-table-column prop="ownerName" label="业主" width="100" />
      <el-table-column prop="room" label="房号" width="90" />
      <el-table-column prop="projectName" label="项目" />
      <el-table-column label="应收"><template #default="{ row }">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="pool" label="池" width="120" />
    </el-table>
  </el-card>
</template>
