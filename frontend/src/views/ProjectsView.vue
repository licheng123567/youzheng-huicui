<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 真 GET /projects（契约客户端，类型来自 schema.d.ts）。资金双线：平台/物业见 commInRate，服务商视角无。
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/projects', { params: { query: { page: page.value, size: size.value } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}
onMounted(load)
</script>

<template>
  <el-card header="项目（GET /projects · 契约客户端 + 数据范围裁剪）">
    <el-table v-loading="loading" :data="items" border @row-click="(r:any)=>router.push(`/projects/${r.id}`)" style="cursor:pointer">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="项目" />
      <el-table-column label="物业"><template #default="{ row }">{{ row.org?.name ?? row.orgName }}</template></el-table-column>
      <el-table-column prop="area" label="区域" width="100" />
      <el-table-column label="收佣比例" width="110">
        <template #default="{ row }">{{ row.commInRate != null ? (row.commInRate * 100).toFixed(2) + '%' : '—（服务商视角无）' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
    </el-table>
    <el-pagination style="margin-top:12px" layout="total, prev, pager, next" :total="total"
      :page-size="size" :current-page="page" @current-change="(p:number)=>{page=p;load()}" />
  </el-card>
</template>
