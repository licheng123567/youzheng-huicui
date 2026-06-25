<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// GET /projects/{id} → oneOf(Project|ProjectForProvider)，服务端按角色返回；viewRole 判别。
const route = useRoute()
const router = useRouter()
const p = ref<any>(null)

onMounted(async () => {
  const { data, error } = await api.GET('/projects/{id}', { params: { path: { id: String(route.params.id) } } })
  if (error || !data) { ElMessage.error('加载失败'); return }
  p.value = data
})
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
  </el-card>
</template>
