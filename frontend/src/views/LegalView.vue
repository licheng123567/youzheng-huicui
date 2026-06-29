<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { components } from '../api/schema'

// 法务·进行中案件（PC）。后端无专属端点：拉 GET /cases 后客户端过滤 legalStage 非空且非 NONE。
// listCases query 无 legalStage 参数（已读 schema 确认），故只能客户端筛。金额 *_cents 分→元。
type CaseStatus = components['schemas']['CaseStatusEnum']
type LegalStage = components['schemas']['LegalStageEnum']
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 状态枚举→中文（同 CasesView 范式）
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

// 法务阶段 LegalStageEnum→中文 + 配色
const STAGE_NAME: Record<string, string> = {
  FUNCTION_LETTER: '职能告知函', LAWYER_LETTER: '律师函', LITIGATION: '诉讼', DELIVERED: '已送达',
}
const stageName = (s?: string) => STAGE_NAME[s ?? ''] ?? s ?? '—'
const STAGE_TAG: Record<string, string> = {
  FUNCTION_LETTER: 'inf', LAWYER_LETTER: 'war', LITIGATION: 'dan', DELIVERED: 'suc',
}
const stageTag = (s?: string) => STAGE_TAG[s ?? ''] ?? 'inf'

// 在法务流程中：legalStage 存在且非 NONE
function inLegal(c: any): boolean {
  var st: LegalStage | undefined = c && c.legalStage
  return !!st && st !== 'NONE'
}

const page = ref(1)
const size = ref(20)

// 拉一页 /cases 后客户端过滤。失败提示不阻断。
async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  const { data, error } = await api.GET('/cases', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  const all: any[] = (data as any)?.items ?? []
  items.value = all.filter(inLegal)
  total.value = (data as any)?.meta?.total ?? 0
}

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

function goCase(id: string) { router.push('/cases/' + id) }

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>法务·进行中案件</div>
      <div class="ops"><span class="note" style="margin:0">GET /cases · 客户端筛 legalStage</span></div>
    </div>

    <div class="note">法务文书的申请/送达/进度在案件详情「操作区·送达存证」内办理。</div>

    <table v-loading="loading" style="margin-top:12px">
      <thead>
        <tr>
          <th style="width:100px">户号</th>
          <th style="width:100px">业主</th>
          <th style="width:90px">房号</th>
          <th style="width:120px">欠费</th>
          <th style="width:120px">法务阶段</th>
          <th style="width:120px">状态</th>
          <th style="width:90px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.acctNo || '—' }}</td>
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag" :class="stageTag(row.legalStage)">{{ stageName(row.legalStage) }}</span></td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusName(row.status) }}</span></td>
          <td><button class="btn df" @click="goCase(String(row.id))">进案件</button></td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="7" style="text-align:center;color:var(--sec);padding:32px 0">暂无法务流程中的案件</td>
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
