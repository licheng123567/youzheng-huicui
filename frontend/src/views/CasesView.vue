<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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

// 状态 → ds-admin .tag 配色（suc/war/dan/inf/pri）
const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

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
function onPage(p: number) { if (p < 1 || p > pageCount.value || p === page.value) return; page.value = p; load() }
// ds-admin .page-bar 用：总页数 + 当前页附近的页码窗口（最多 5 个）
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

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
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>案件</div>
      <div class="ops"><span class="note" style="margin:0">GET /cases · 共 {{ total }} · 跨层级筛选 + 批次直达</span></div>
    </div>

    <!-- 筛选栏：项目/批次/状态/关键字（q 受脱敏+scope 裁剪 BR-M8-09） -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>项目</span>
        <select class="inp" v-model="filters.projectId" @change="search">
          <option value="">全部项目</option>
          <option v-for="p in projects" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
        </select>
      </div>
      <div class="fi">
        <span>批次号</span>
        <input class="inp" v-model="filters.batchId" placeholder="批次号直达" style="min-width:150px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>状态</span>
        <select class="inp" v-model="filters.status" @change="search">
          <option value="">全部状态</option>
          <option v-for="s in STATUS_OPTS" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
      </div>
      <div class="fi">
        <span>关键字</span>
        <input class="inp" v-model="filters.q" placeholder="手机号/户号/业主名" @keyup.enter="search" />
      </div>
      <div class="fi">
        <button class="btn" @click="search">查询</button>
        <button class="btn df" @click="reset">重置</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:100px">户号</th>
          <th style="width:100px">业主</th>
          <th style="width:90px">房号</th>
          <th>项目</th>
          <th style="width:120px">应收</th>
          <th style="width:120px">状态</th>
          <th style="width:120px">池</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id" class="row-click" @click="router.push(`/cases/${row.id}`)">
          <td>{{ row.acctNo || '—' }}</td>
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusName(row.status) }}</span></td>
          <td>{{ row.pool || '—' }}</td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="7" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="onPage(page - 1)">‹</div>
      <div v-for="p in pages" :key="p" class="pg" :class="{ on: p === page }" @click="onPage(p)">{{ p }}</div>
      <div class="pg" @click="onPage(page + 1)">›</div>
    </div>
  </div>
</template>
