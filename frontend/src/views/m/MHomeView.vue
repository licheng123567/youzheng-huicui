<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../../stores/auth'
import { api } from '../../api/client'

// 移动工作台:今日概览(workbench.kpis) + 待办(workbench.todos) + 案件清单(GET /cases 本范围)。
const auth = useAuth()
const router = useRouter()
const wb = ref<any>(null)
const cases = ref<any[]>([])
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
const urgBadge = (u?: string) => (u === 'HIGH' ? 'b-rd' : u === 'MED' ? 'b-or' : 'b-bl')

const CAT_LABEL: Record<string, string> = {
  PROMISE_DUE: '承诺到期', RELEASE_WARN: '临近释放', TICKET_RECEIPT: '工单回执',
  NEW_ASSIGNED: '新分配', LEGAL_DELIVERY: '法务待送达', REPAY_MARK: '回款待标',
  PAYLINK_SEND: '链接待发', REDUCE_APPROVE: '减免待批',
  T2_RETURN_WARN: '即将退回平台', T1_DISPATCH_WARN: '待派单超时',
}
const kpis = computed<any[]>(() => wb.value?.kpis ?? [])
const todos = computed<any[]>(() => wb.value?.todos ?? [])

async function load() {
  const [{ data: w }, { data: c }] = await Promise.all([
    api.GET('/workbench', {}),
    api.GET('/cases', { params: { query: { page: 1, size: 20 } } as any }),
  ])
  wb.value = w
  cases.value = (c as any)?.items ?? []
}
function openCase(id: any) { router.push(`/m/cases/${id}`) }
function openTodo(t: any) { if (t.caseId) openCase(t.caseId); else router.push('/m/cases') }
onMounted(load)
</script>

<template>
  <div>
    <div class="sec" style="margin-top:2px">本日概览</div>
    <div class="todoh" v-if="kpis.length">
      <div class="c" v-for="k in kpis" :key="k.label">
        <div class="n" style="color:#2563eb">{{ k.value }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
    </div>
    <div class="mini" v-else style="margin-bottom:10px">暂无概览数据</div>

    <template v-if="todos.length">
      <div class="sec">待办（点条目进案件）</div>
      <div class="mc" v-for="(t, i) in todos" :key="i" @click="openTodo(t)" style="cursor:pointer">
        <div class="row">
          <b>{{ t.title || CAT_LABEL[t.category] || t.category }}</b>
          <span class="badge" :class="urgBadge(t.urgency)">{{ CAT_LABEL[t.category] || t.category }}</span>
        </div>
        <div class="row" style="margin-top:6px" v-if="t.dueLabel || t.ownerName">
          <span class="mini">{{ t.ownerName }}<template v-if="t.room"> · {{ t.room }}</template></span>
          <span class="mini">{{ t.dueLabel }}</span>
        </div>
      </div>
    </template>

    <div class="sec">今日案件清单（点击直接作业）</div>
    <div class="mc" v-for="cs in cases" :key="cs.id" @click="openCase(cs.id)" style="cursor:pointer">
      <div class="row">
        <b>{{ cs.ownerName || '—' }}<template v-if="cs.room"> · {{ cs.room }}</template></b>
        <span class="due">{{ yuan(cs.dueCents) }}</span>
      </div>
      <div class="row" style="margin-top:6px">
        <span class="mini">{{ cs.projectName || cs.acctNo || '' }}</span>
        <span class="badge" :class="statusBadge(cs.status)">{{ statusLabel(cs.status) }}</span>
      </div>
    </div>
    <div class="mini" v-if="!cases.length" style="text-align:center;margin-top:10px">暂无案件</div>
  </div>
</template>
