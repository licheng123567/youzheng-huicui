<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'

// 案件/业主搜索(GET /search · BR-M4-22 · 数据范围裁剪 + 未持有公海脱敏)。
const route = useRoute(); const router = useRouter()
const q = ref(String(route.query.q ?? ''))
const items = ref<any[]>([]); const loading = ref(false); const searched = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

async function run() {
  if (!q.value.trim()) return
  loading.value = true; searched.value = true
  const { data } = await api.GET('/search', { params: { query: { q: q.value.trim(), type: 'case', page: 1, size: 50 } } as any })
  loading.value = false
  items.value = (data as any)?.items ?? []
}
watch(() => route.query.q, (v) => { q.value = String(v ?? ''); run() })
onMounted(run)
</script>

<template>
  <el-card>
    <template #header>
      <el-input v-model="q" placeholder="业主姓名/房号/户号/电话" style="width:300px" @keyup.enter="run">
        <template #append><el-button @click="run">搜索</el-button></template>
      </el-input>
      <span style="color:#909399;font-size:12px;margin-left:10px">按数据范围裁剪 · 未持有公海案件脱敏</span>
    </template>
    <el-table v-loading="loading" :data="items" border @row-click="(r:any)=>router.push(`/cases/${r.caseId}`)" style="cursor:pointer">
      <el-table-column prop="acctNo" label="户号" /><el-table-column prop="ownerName" label="业主" />
      <el-table-column prop="room" label="房号" />
      <el-table-column label="应收"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" /><el-table-column prop="projectName" label="项目" />
    </el-table>
    <el-empty v-if="searched && !items.length && !loading" description="无命中" :image-size="50" />
  </el-card>
</template>
