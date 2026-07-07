<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { caseStatusLabel } from '../constants/enums'

// 案件管理：按角色分支——批次优先(PL/PC/SA/SE/VL 走「选择批次→案件明细」，对标原型「案件入口批次优先」)；
// 催收员(CO)看"我的案件"扁平私海清单(CO 入口是私海/公海，非批次)。
const auth = useAuth()
const router = useRouter()

const yuan = (c?: number) => c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN')
const pct = (r?: number) => r != null ? (r * 100).toFixed(1) + '%' : '—'

// ── 角色判定 ──
const isCollector = computed(() => auth.me?.role === 'CO')
const isCoordinator = computed(() => auth.me?.role === 'PC')
// 批次优先入口 = 除催收员外的所有可见「案件管理」角色（PC 亦批次优先，对标原型 PC 案件管理）。
const isManagerRole = computed(() => auth.me?.role !== 'CO')

// 持有上限（CO 行级展示用，默认 50）
const HOLDCAP = 50

// ── 批次列表（管理角色）──
const batches = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', DISPATCHED: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf', OPEN_POOL: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

const filters = reactive({ projectId: '', status: '', q: '' })
const page = ref(1); const size = ref(20)

async function loadBatches() {
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

function search() { page.value = 1; isManagerRole.value ? loadBatches() : loadCases() }
function reset() { filters.projectId = ''; filters.status = ''; filters.q = ''; search() }
function onPage(p: number) { if (p < 1 || p > pageCount.value || p === page.value) return; page.value = p; isManagerRole.value ? loadBatches() : loadCases() }
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

function openImport() { router.push('/batches?openImport=1') }
function viewBatch(row: any) { router.push(`/batches/${row.id}`) }

// ── 我的案件（CO / PC）──
const cases = ref<any[]>([])
const caseFilter = reactive({ q: '', status: '', projectId: '' })

async function loadCases() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (caseFilter.q) query.q = caseFilter.q
  if (caseFilter.status) query.status = caseFilter.status
  if (caseFilter.projectId) query.projectId = caseFilter.projectId
  const { data } = await api.GET('/cases', { params: { query } as any })
  loading.value = false
  cases.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function caseSearch() { page.value = 1; loadCases() }
function caseReset() { caseFilter.q = ''; caseFilter.status = ''; caseFilter.projectId = ''; caseSearch() }

function viewCase(row: any) { router.push(`/cases/${row.id}`) }
function goSea() { router.push('/sea') }

const holdingCount = computed(() => total.value)

// auth.me 是异步拉取的：onMounted 时可能未就绪，role 为空会把 CO/PC 误判成管理角色（走批次分支导致"我的案件"恒空）。
// 改为 me 就绪后再按角色加载，immediate 兼容已就绪场景。
watch(() => auth.me, (me) => {
  if (!me) return
  if (isManagerRole.value) loadBatches()
  else loadCases()
}, { immediate: true })
</script>

<template>
  <!-- ================================================================ -->
  <!--  管理角色 (PL/SA/SE/VL)：批次列表入口                                -->
  <!-- ================================================================ -->
  <div v-if="isManagerRole" class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>案件管理 — 选择批次查看案件明细</div>
      <div class="ops">
        <span class="note" style="margin:0">共 {{ total }} 个批次</span>
        <!-- 协调员(PC)不导入批次（导入是负责人/平台职责，对标原型 PC 案件管理无导入入口） -->
        <button v-if="(auth.has('batch.import') || auth.has('proj.edit')) && !isCoordinator" class="btn sm" @click="openImport">+ 导入批次</button>
      </div>
    </div>

    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>搜索</span>
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

  <!-- ================================================================ -->
  <!--  一线角色 (CO / PC)："我的案件"                                     -->
  <!-- ================================================================ -->
  <div v-else class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>我的案件</div>
      <div class="ops">
        <span v-if="isCollector" class="tag pri">持有 {{ holdingCount }}/{{ HOLDCAP }}（持有上限）</span>
        <span v-else class="note" style="margin:0">本物业案件 · 共 {{ total }} 件</span>
      </div>
    </div>

    <!-- 搜索栏 -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>搜索</span>
        <input class="inp" v-model="caseFilter.q" placeholder="业主/房号" style="min-width:140px" @keyup.enter="caseSearch" />
      </div>
      <div class="fi">
        <span>状态</span>
        <select class="inp" v-model="caseFilter.status" @change="caseSearch">
          <option value="">全部状态</option>
          <option value="IN_PROGRESS">催收中</option>
          <option value="PROMISED">承诺缴费</option>
          <option value="SETTLED">已结清</option>
          <option value="WITHDRAWN">撤案</option>
          <option value="BAD_DEBT">坏账</option>
        </select>
      </div>
      <div class="fi">
        <button class="btn" @click="caseSearch">查询</button>
        <button class="btn df" @click="caseReset">重置</button>
      </div>
    </div>

    <table v-if="cases.length" v-loading="loading">
      <thead>
        <tr>
          <th>业主</th>
          <th>房号</th>
          <th>项目</th>
          <th>批次</th>
          <th>应收</th>
          <th>状态</th>
          <th>联系方式</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in cases" :key="row.id" class="row-click" @click="viewCase(row)">
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td>{{ row.batchCode || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ caseStatusLabel(row.status) }}</span></td>
          <td>{{ row.contactPhone || '—' }}</td>
          <td @click.stop><a class="btn txt" @click="viewCase(row)">查看</a></td>
        </tr>
      </tbody>
    </table>

    <!-- 空态 -->
    <div v-if="!loading && !cases.length" class="empty-state" style="text-align:center;padding:40px 0;color:var(--ph)">
      <div style="font-size:36px">🪧</div>
      <div class="note" style="margin-top:8px">暂无持有案件，去案件公海抢单吧。</div>
      <button class="btn pl" style="margin-top:12px" @click="goSea">去案件公海 →</button>
    </div>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="onPage(page - 1)">‹</div>
      <div v-for="p in pages" :key="p" class="pg" :class="{ on: p === page }" @click="onPage(p)">{{ p }}</div>
      <div class="pg" @click="onPage(page + 1)">›</div>
    </div>
  </div>
</template>
