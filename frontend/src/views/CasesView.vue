<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { caseStatusLabel } from '../constants/enums'

// 案件管理（批次优先）：批次列表 → 点击进入批次明细 → 案件列表。
// 对标高保真原型 view==='cases' 双态：批次列表 / 批次明细(含案件)。
const auth = useAuth()
const router = useRouter()
const batches = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

const yuan = (c?: number) => c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN')
const pct = (r?: number) => r != null ? (r * 100).toFixed(1) + '%' : '—'

// 批次列表状态 tag 配色
const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', DISPATCHED: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf', OPEN_POOL: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

// 筛选
const filters = reactive({ projectId: '', status: '', q: '' })
const page = ref(1); const size = ref(20)

async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (filters.projectId) query.projectId = filters.projectId
  if (filters.status) query.status = filters.status
  if (filters.q) query.q = filters.q
  const { data } = await api.GET('/batches', { params: { query } as any })
  loading.value = false
  batches.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function search() { page.value = 1; load() }
function reset() { filters.projectId = ''; filters.status = ''; filters.q = ''; search() }
function onPage(p: number) { if (p < 1 || p > pageCount.value || p === page.value) return; page.value = p; load() }
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

// 导入批次（复用 BatchesView 的导入弹窗逻辑，直接导航到批次页并触发导入）
function openImport() { router.push('/batches?openImport=1') }

function viewBatch(row: any) { router.push(`/batches/${row.id}`) }

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>案件管理 — 选择批次查看案件明细</div>
      <div class="ops">
        <span class="note" style="margin:0">共 {{ total }} 个批次</span>
        <button v-if="auth.has('batch.import') || auth.has('proj.edit')" class="btn sm" @click="openImport">+ 导入批次</button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>项目</span>
        <input class="inp" v-model="filters.q" placeholder="批次号/项目名" style="min-width:160px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>状态</span>
        <select class="inp" v-model="filters.status" @change="search">
          <option value="">全部状态</option>
          <option value="PENDING_DISPATCH">待派单</option>
          <option value="IN_PROGRESS">催收中</option>
          <option value="SETTLED">已结清</option>
          <option value="VOIDED">已作废</option>
        </select>
      </div>
      <div class="fi">
        <button class="btn" @click="search">查询</button>
        <button class="btn df" @click="reset">重置</button>
      </div>
    </div>

    <div class="alert info" style="margin-bottom:12px">点击批次行进入案件明细（项目 → 批次 → 案件）。</div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>批次号</th>
          <th>项目</th>
          <th style="width:80px">案件数</th>
          <th style="width:120px">应收金额</th>
          <th style="width:120px">已收金额</th>
          <th style="width:90px">回款率</th>
          <th style="width:90px">状态</th>
          <th style="width:120px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in batches" :key="row.id" class="row-click" @click="viewBatch(row)">
          <td><b>{{ row.code }}</b></td>
          <td>{{ row.projectName || '—' }}</td>
          <td class="num">{{ row.totalCases ?? '—' }}</td>
          <td class="num">{{ yuan(row.dueTotalCents) }}</td>
          <td class="num">{{ yuan(row.repaidTotalCents) }}</td>
          <td class="num">{{ pct(row.repayRate) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ caseStatusLabel(row.status) }}</span></td>
          <td @click.stop><a class="btn txt" @click="viewBatch(row)">查看案件明细 ›</a></td>
        </tr>
        <tr v-if="!loading && !batches.length">
          <td colspan="8" style="text-align:center;color:var(--sec);padding:32px 0">暂无批次，点击「+ 导入批次」导入催收单。</td>
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
