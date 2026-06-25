<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// GET /batches → BatchView(平台双线/物业只收佣/服务商只付佣)。资金双线字段级隔离。
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  const { data, error } = await api.GET('/batches', { params: { query: { page: 1, size: 20 } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
})
</script>

<template>
  <el-card :header="`批次（GET /batches · 共 ${total}）`">
    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="批次号" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column label="收佣比例" width="120">
        <template #default="{ row }">{{ row.commInRate != null ? row.commInRate + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="付佣比例" width="120">
        <template #default="{ row }">{{ row.payOutRate != null ? row.payOutRate + '%' : '—（物业视角不可见）' }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>
