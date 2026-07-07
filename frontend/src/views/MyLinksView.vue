<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import PayLinkCard from '../components/PayLinkCard.vue'

// 我的已发缴费链接跟踪（CO/PC），对标原型 §已发缴费链接 index.html:736-763。
// 数据源：GET /me/pay-links（仅本人 created_by，服务端筛选+分页）。
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const sending = ref<Record<string, boolean>>({})
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

const STATUS_LABEL: Record<string, string> = {
  PENDING_VIEW: '待查看', VIEWED_UNPAID: '已读未缴', PAID: '已缴费', EXPIRED: '已过期',
}
const STATUS_TAG: Record<string, string> = {
  PAID: 'suc', EXPIRED: 'dan', VIEWED_UNPAID: 'war', PENDING_VIEW: 'inf',
}
const statusLabel = (s?: string) => STATUS_LABEL[s ?? ''] ?? s ?? '—'
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

const filter = reactive({ q: '', project: '', batch: '', status: '', from: '', to: '' })
const page = ref(1)
const size = ref(20)

// 项目/批次筛选项：由已加载数据去重派生（无独立项目/批次清单端点权限假设）。
const projectOptions = computed(() => Array.from(new Set(items.value.map((r) => r.project).filter(Boolean))))
const batchOptions = computed(() => Array.from(new Set(items.value.map((r) => r.batch).filter(Boolean))))

async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (filter.q.trim()) query.q = filter.q.trim()
  if (filter.project) query.project = filter.project
  if (filter.batch) query.batch = filter.batch
  if (filter.status) query.status = filter.status
  if (filter.from) query.from = filter.from
  if (filter.to) query.to = filter.to
  const { data, error } = await api.GET('/me/pay-links', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function applyFilter() { page.value = 1; load() }
function resetFilter() {
  filter.q = ''; filter.project = ''; filter.batch = ''; filter.status = ''; filter.from = ''; filter.to = ''
  applyFilter()
}

function goCase(id: string) { router.push('/cases/' + id) }

async function resend(row: any) {
  const id = String(row.id)
  if (sending.value[id]) return
  sending.value[id] = true
  const { error } = await api.POST('/pay-links/{id}/resend', { params: { path: { id } } } as any)
  sending.value[id] = false
  if (error) { ElMessage.error('重发失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已重发缴费链接')
}

// 行内展开：链接文本 + 二维码（可下载）+ 短信发送，交给经办人转交业主（H5 页 GET /pay/{token}）
const expanded = ref<Record<string, boolean>>({})
function toggleExpand(row: any) { const id = String(row.id); expanded.value[id] = !expanded.value[id] }

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
      <div class="t"><span class="bar"></span>已发缴费链接</div>
      <div class="ops"><span class="note" style="margin:0">{{ items.length }}/{{ total }} 条</span></div>
    </div>

    <div class="toolbar" style="margin-bottom:10px">
      <input class="inp" v-model="filter.q" placeholder="搜索 业主 / 房号" aria-label="搜索" @keyup.enter="applyFilter">
      <select class="inp" v-model="filter.project" aria-label="项目筛选" @change="applyFilter">
        <option value="">项目：全部</option>
        <option v-for="p in projectOptions" :key="p" :value="p">{{ p }}</option>
      </select>
      <select class="inp" v-model="filter.batch" aria-label="批次筛选" @change="applyFilter">
        <option value="">批次：全部</option>
        <option v-for="b in batchOptions" :key="b" :value="b">{{ b }}</option>
      </select>
      <select class="inp" v-model="filter.status" aria-label="状态筛选" @change="applyFilter">
        <option value="">状态：全部</option>
        <option value="PENDING_VIEW">待查看</option>
        <option value="VIEWED_UNPAID">已读未缴</option>
        <option value="PAID">已缴费</option>
        <option value="EXPIRED">已过期</option>
      </select>
      <input class="inp" type="date" v-model="filter.from" aria-label="发送起始" style="min-width:150px" @change="applyFilter">
      <span class="note" style="margin:0">~</span>
      <input class="inp" type="date" v-model="filter.to" aria-label="发送结束" style="min-width:150px" @change="applyFilter">
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>发送时间</th><th>业主</th><th>房号</th><th>项目</th><th>批次</th><th>金额</th><th>渠道</th><th>状态</th><th style="width:170px">操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in items" :key="row.id">
          <tr>
            <td>{{ (row.sentAt || '').slice(0, 16).replace('T', ' ') }}</td>
            <td>{{ row.ownerName || '—' }}</td>
            <td>{{ row.room || '—' }}</td>
            <td>{{ row.project || '—' }}</td>
            <td>{{ row.batch || '—' }}</td>
            <td class="num">{{ yuan(row.amountCents) }}</td>
            <td>{{ row.channel === 'SMS' ? '短信' : '微信转发' }}</td>
            <td><span class="tag" :class="statusTag(row.status)">{{ statusLabel(row.status) }}</span></td>
            <td>
              <a class="btn txt" @click="goCase(String(row.caseId))">进入案件</a>
              <a v-if="row.status !== 'PAID'" class="btn txt" :class="{ df: sending[String(row.id)] }" @click="resend(row)">重发</a>
              <a class="btn txt" @click="toggleExpand(row)">{{ expanded[String(row.id)] ? '收起' : '链接/二维码' }}</a>
            </td>
          </tr>
          <tr v-if="expanded[String(row.id)]">
            <td colspan="9" style="background:var(--bg)"><PayLinkCard :token="row.token" @send-sms="resend(row)" /></td>
          </tr>
        </template>
        <tr v-if="!loading && !items.length">
          <td colspan="9" style="text-align:center;color:var(--sec);padding:32px 0">暂无已发送的缴费链接</td>
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
