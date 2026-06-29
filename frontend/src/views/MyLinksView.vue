<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 我的缴费链接（PC/CO）。后端无 pay-links 列表端点，做成「缴费链接管理入口」：
//  GET /cases（本范围分页）列表，每行可「发缴费链接」(POST /cases/{id}/pay-links {channel:'SMS'}) + 「进案件」。
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const sending = ref<Record<string, boolean>>({}) // 行级发送中态，初始即初始化
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

const STATUS_NAME: Record<string, string> = {
  PENDING_DISPATCH: '待派单', PROVIDER_SEA: '服务商公海', IN_PROGRESS: '催收中',
  PROMISED: '已承诺', SETTLED: '已结清', WITHDRAWN: '已撤回', BAD_DEBT: '坏账', VOIDED: '已作废',
}
const statusName = (s?: string) => STATUS_NAME[s ?? ''] ?? s ?? '—'
const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf', WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

const page = ref(1)
const size = ref(20)

async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  const { data, error } = await api.GET('/cases', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

// 发缴费链接（短信渠道）。行级防重入。
async function sendLink(row: any) {
  const id = String(row.id)
  if (sending.value[id]) return
  sending.value[id] = true
  const { error } = await api.POST('/cases/{id}/pay-links', {
    params: { path: { id } },
    body: { channel: 'SMS' } as any,
  } as any)
  sending.value[id] = false
  if (error) { ElMessage.error('发送失败'); return }
  ElMessage.success('缴费链接已发起（短信）')
}

function goCase(id: string) { router.push('/cases/' + id) }

function onPage(p: number) {
  if (p < 1 || p > pageCount.value || p === page.value) return
  page.value = p
  load()
}
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>我的缴费链接</div>
      <div class="ops"><span class="note" style="margin:0">GET /cases · 按案件发起缴费链接</span></div>
    </div>

    <div class="alert info">缴费链接按案件发起；集中列表（已发/状态/重发）待后端 GET /pay-links。</div>

    <table v-loading="loading" style="margin-top:12px">
      <thead>
        <tr>
          <th style="width:100px">户号</th>
          <th style="width:100px">业主</th>
          <th style="width:90px">房号</th>
          <th style="width:120px">欠费</th>
          <th style="width:120px">状态</th>
          <th style="width:200px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.acctNo || '—' }}</td>
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusName(row.status) }}</span></td>
          <td>
            <button class="btn" :disabled="sending[String(row.id)]" @click="sendLink(row)">发缴费链接</button>
            <button class="btn df" style="margin-left:6px" @click="goCase(String(row.id))">进案件</button>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="6" style="text-align:center;color:var(--sec);padding:32px 0">暂无案件</td>
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
