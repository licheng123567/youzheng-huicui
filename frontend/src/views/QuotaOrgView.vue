<script setup lang="ts">
// 组织额度详情页 /quota/:orgId（v1.19.0·平台侧从额度管理列表点组织进入）。
// 内容复用 QuotaOrgDetail（余额卡+充值 / 用量分析[月·日+明细下钻] / 充值流水）。
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QuotaOrgDetail from '../components/QuotaOrgDetail.vue'

const route = useRoute()
const router = useRouter()
const orgId = computed(() => String(route.params.orgId ?? ''))
const detail = ref<any>(null)
const orgName = computed(() => detail.value?.orgName ?? '')
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t">
        <span class="bar"></span>
        <a class="link" @click="router.push('/quota')">额度管理</a>
        <span style="margin:0 6px;color:var(--sec)">/</span>
        {{ orgName || '组织额度' }}
      </div>
      <div class="ops">
        <button class="btn sm" @click="router.push('/quota')">‹ 返回组织列表</button>
      </div>
    </div>
    <QuotaOrgDetail ref="detail" :org-id="orgId" />
  </div>
</template>
