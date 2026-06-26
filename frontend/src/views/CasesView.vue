<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { components } from '../api/schema'

// GET /cases（契约客户端）。跨层级筛选：项目/批次/状态/关键字(q) + 批次号直达。金额 *_cents 分→元展示。
type CaseStatus = components['schemas']['CaseStatusEnum']
const route = useRoute()
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const projects = ref<any[]>([]) // 项目下拉（GET /projects 填 projectId）
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 状态枚举 8 值（CaseStatusEnum）→ 中文标签（同 SeaView poolName 范式）
const STATUS_OPTS: { value: CaseStatus; label: string }[] = [
  { value: 'PENDING_DISPATCH', label: '待派单' },
  { value: 'PROVIDER_SEA', label: '服务商公海' },
  { value: 'IN_PROGRESS', label: '催收中' },
  { value: 'PROMISED', label: '已承诺' },
  { value: 'SETTLED', label: '已结清' },
  { value: 'WITHDRAWN', label: '已撤回' },
  { value: 'BAD_DEBT', label: '坏账' },
  { value: 'VOIDED', label: '已作废' },
]
const statusName = (s?: string) => STATUS_OPTS.find((o) => o.value === s)?.label ?? s ?? '—'

// 筛选条件（数组/对象字段初始即初始化，防白屏）。page 受分页控件驱动。
const filters = reactive<{ projectId: string; batchId: string; status: CaseStatus | ''; q: string }>({
  projectId: '',
  batchId: '',
  status: '',
  q: '',
})
const page = ref(1)
const size = ref(20)

// 项目下拉数据（按 scope 过滤）。失败不阻断列表。
async function loadProjects() {
  const { data } = await api.GET('/projects', { params: { query: { page: 1, size: 200 } } as any })
  projects.value = (data as any)?.items ?? []
}

// 可复用列表请求：把 filters + 分页透传 /cases（空串不传，让契约 query 缺省）。
async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (filters.projectId) query.projectId = filters.projectId
  if (filters.batchId) query.batchId = filters.batchId
  if (filters.status) query.status = filters.status
  if (filters.q) query.q = filters.q
  const { data, error } = await api.GET('/cases', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}

// 查询：任何筛选变更都把 page 归 1 再重查。
function search() { page.value = 1; load() }
// 重置：清空筛选回到首页。
function reset() {
  filters.projectId = ''
  filters.batchId = ''
  filters.status = ''
  filters.q = ''
  search()
}
// 分页变更（仅 page，size 固定）→ 复用 load。
function onPage(p: number) { page.value = p; load() }

// 从批次页/全局搜索『批次号直达』：onMounted 读 route.query.batchId（及 projectId/status）作初始筛选。
onMounted(() => {
  const q = route.query
  if (typeof q.batchId === 'string') filters.batchId = q.batchId
  if (typeof q.projectId === 'string') filters.projectId = q.projectId
  if (typeof q.status === 'string' && STATUS_OPTS.some((o) => o.value === q.status)) filters.status = q.status as CaseStatus
  if (typeof q.q === 'string') filters.q = q.q
  loadProjects()
  load()
})
</script>

<template>
  <el-card :header="`案件（GET /cases · 共 ${total} · 跨层级筛选+批次直达）`">
    <!-- 筛选栏：项目/批次/状态/关键字（q 受脱敏+scope 裁剪 BR-M8-09） -->
    <el-form :inline="true" style="margin-bottom:8px" @submit.prevent="search">
      <el-form-item label="项目">
        <el-select v-model="filters.projectId" placeholder="全部项目" clearable filterable style="width:180px" @change="search">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="String(p.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="批次号">
        <el-input v-model="filters.batchId" placeholder="批次号直达" clearable style="width:150px" @keyup.enter="search" @clear="search" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width:140px" @change="search">
          <el-option v-for="s in STATUS_OPTS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="filters.q" placeholder="手机号/户号/业主名" clearable style="width:180px" @keyup.enter="search" @clear="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" border @row-click="(r:any)=>router.push(`/cases/${r.id}`)" style="cursor:pointer">
      <el-table-column prop="acctNo" label="户号" width="100" />
      <el-table-column prop="ownerName" label="业主" width="100" />
      <el-table-column prop="room" label="房号" width="90" />
      <el-table-column prop="projectName" label="项目" />
      <el-table-column label="应收"><template #default="{ row }">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
      <el-table-column prop="pool" label="池" width="120" />
    </el-table>

    <el-pagination
      v-if="total > size"
      style="margin-top:12px;justify-content:flex-end"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPage"
    />
  </el-card>
</template>
