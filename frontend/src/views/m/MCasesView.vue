<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../../api/client'

// 移动案件列表:关键字 + 状态筛选(GET /cases),点卡片进作业。
const router = useRouter()
const items = ref<any[]>([])
const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

const STATUS_LABEL: Record<string, string> = {
  PENDING_DISPATCH: '待派单', PROVIDER_SEA: '服务商公海', IN_PROGRESS: '催收中',
  PROMISED: '已承诺', SETTLED: '已结清', WITHDRAWN: '已撤回', BAD_DEBT: '坏账', VOIDED: '已作废',
}
const STATUS_BADGE: Record<string, string> = {
  SETTLED: 'b-gr', PROMISED: 'b-gr', IN_PROGRESS: 'b-bl',
  PENDING_DISPATCH: 'b-gy', PROVIDER_SEA: 'b-gy', WITHDRAWN: 'b-gy', BAD_DEBT: 'b-rd', VOIDED: 'b-rd',
}
const statusLabel = (s?: string) => STATUS_LABEL[s ?? ''] ?? s ?? '—'
const statusBadge = (s?: string) => STATUS_BADGE[s ?? ''] ?? 'b-gy'

// 快捷筛选段(全部/催收中/已承诺/已结清)
const FILTERS = [
  { key: '', label: '全部' },
  { key: 'IN_PROGRESS', label: '催收中' },
  { key: 'PROMISED', label: '已承诺' },
  { key: 'SETTLED', label: '已结清' },
]
const filters = reactive<{ status: string; q: string }>({ status: '', q: '' })

async function load() {
  loading.value = true
  const query: Record<string, any> = { page: 1, size: 50 }
  if (filters.status) query.status = filters.status
  if (filters.q) query.q = filters.q
  const { data, error } = await api.GET('/cases', { params: { query } as any })
  loading.value = false
  items.value = error ? [] : ((data as any)?.items ?? [])
}
function pickFilter(k: string) { filters.status = k; load() }
function openCase(id: any) { router.push(`/m/cases/${id}`) }
onMounted(load)
</script>

<template>
  <div>
    <input class="inp" style="width:100%;margin-bottom:10px" v-model="filters.q" placeholder="🔍 搜索业主 / 房号 / 手机号" @keyup.enter="load" />
    <div class="todoh" style="margin-bottom:10px">
      <span
        v-for="f in FILTERS"
        :key="f.key"
        class="badge"
        :class="filters.status === f.key ? 'b-bl' : 'b-gy'"
        style="padding:6px 12px;cursor:pointer"
        @click="pickFilter(f.key)"
      >{{ f.label }}</span>
    </div>

    <div class="mc" v-for="cs in items" :key="cs.id" @click="openCase(cs.id)" style="cursor:pointer">
      <div class="row">
        <b>{{ cs.ownerName || '—' }}<template v-if="cs.room"> · {{ cs.room }}</template></b>
        <span class="due">{{ yuan(cs.dueCents) }}</span>
      </div>
      <div class="row" style="margin-top:6px">
        <span class="mini">{{ cs.projectName || cs.acctNo || '' }}<template v-if="cs.pool"> · {{ cs.pool }}</template></span>
        <span class="badge" :class="statusBadge(cs.status)">{{ statusLabel(cs.status) }}</span>
      </div>
    </div>
    <div class="mini" v-if="loading" style="text-align:center;margin-top:10px">加载中…</div>
    <div class="mini" v-else-if="!items.length" style="text-align:center;margin-top:10px">暂无案件</div>
    <div class="mini" style="text-align:center;margin-top:8px;line-height:1.6">号码取自案件主数据，拨打调起本机报号器；通话系统自动录音→后台上传解析。</div>
  </div>
</template>
