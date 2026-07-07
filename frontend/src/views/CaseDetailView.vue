<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CaseThreeColumn from '../components/CaseThreeColumn.vue'
import { caseStatusLabel } from '../constants/enums'

// 路由薄壳：三栏详情本体已抽成 CaseThreeColumn（复用于工作台「今日必办」预览，对标原型 <case-three-col>）。
// 头条卡 + 返回按钮对标原型「案件详情」头部（index.html §案件详情）；原型用 view 状态栈返回上一视图，
// 我们是真路由，等价语义用 router.back()（无历史时回退到案件列表)。
const route = useRoute()
const router = useRouter()
const caseId = computed(() => String(route.params.id))
const headerCase = ref<any>(null)
function onLoaded(detail: any) { headerCase.value = detail?.case ?? null }

const CASE_STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const caseStatusTag = (s?: string) => CASE_STATUS_TAG[s ?? ''] ?? 'inf'

function goBack() {
  if (window.history.state?.back) router.back()
  else router.push('/cases')
}
</script>

<template>
  <div class="card-h" style="background:#fff;border:1px solid var(--bd);border-radius:8px;padding:12px 16px;margin-bottom:12px;box-shadow:0 1px 4px rgba(20,40,90,.04)">
    <div class="t">
      <span class="bar"></span>
      {{ headerCase?.ownerName || '案件详情' }}
      <template v-if="headerCase?.room"> · {{ headerCase.room }}</template>
      <span v-if="headerCase?.status" class="tag" :class="caseStatusTag(headerCase.status)" :title="headerCase.status">{{ caseStatusLabel(headerCase.status) }}</span>
    </div>
    <div class="ops">
      <button class="btn df sm" @click="goBack">← 返回</button>
    </div>
  </div>
  <CaseThreeColumn :case-id="caseId" @loaded="onLoaded" />
</template>
