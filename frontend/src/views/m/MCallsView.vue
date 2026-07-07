<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../../api/client'

// 移动通话记录:GET /recordings,点条目进对应案件。
const router = useRouter()
const items = ref<any[]>([])
const loading = ref(true)

const ST_LABEL: Record<string, string> = { READY: '已就绪', PARSING: '解析中', FAILED: '失败', QUOTA_BLOCKED: '余额不足', PENDING: '待处理' }
const ST_BADGE: Record<string, string> = { READY: 'b-gr', PARSING: 'b-bl', FAILED: 'b-rd', QUOTA_BLOCKED: 'b-or', PENDING: 'b-gy' }
const stLabel = (s?: string) => ST_LABEL[s ?? ''] ?? s ?? '—'
const stBadge = (s?: string) => ST_BADGE[s ?? ''] ?? 'b-gy'
const fmtDur = (s?: number) => (s == null ? '—' : `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`)

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/recordings', { params: { query: { page: 1, size: 50 } } as any })
  loading.value = false
  items.value = error ? [] : ((data as any)?.items ?? [])
}
function open(r: any) { if (r.caseId) router.push(`/m/cases/${r.caseId}`) }
onMounted(load)
</script>

<template>
  <div>
    <div class="sec" style="margin-top:2px">通话记录</div>
    <div class="mc" v-for="r in items" :key="r.id" @click="open(r)" :style="r.caseId ? 'cursor:pointer' : ''">
      <div class="row">
        <b>{{ r.ownerName || r.phone || '通话' }}</b>
        <span class="badge" :class="stBadge(r.status)">{{ stLabel(r.status) }}</span>
      </div>
      <div class="row" style="margin-top:4px">
        <span class="mini">{{ (r.recordedAt || '').slice(0, 16).replace('T', ' ') }}</span>
        <span class="mini">时长 {{ fmtDur(r.durationSec) }}</span>
      </div>
      <div class="mini" style="margin-top:4px;color:#4b5563" v-if="r.phone">号码 {{ r.phone }} · 来源 {{ r.source || '—' }}</div>
    </div>
    <div class="mini" v-if="loading" style="text-align:center;margin-top:10px">加载中…</div>
    <div class="mini" v-else-if="!items.length" style="text-align:center;margin-top:10px">暂无通话记录</div>
  </div>
</template>
