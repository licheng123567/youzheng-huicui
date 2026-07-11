<script setup lang="ts">
// /settlement 入口按角色分叉（v1.16.0 平台双线总账）：
//   平台(SA/SE) → PlatformReconView（批次维一行双线 应收/已收/应付/已付+毛利+明细下钻+组单）；
//   物业(PL/PC) → SettlementView（IN 单线只读，行为与拆分前逐位一致）。
// VL 的 /settlement-out 不经此入口，直指 SettlementView（OUT 线）。
import { computed } from 'vue'
import { useAuth } from '../stores/auth'
import PlatformReconView from './PlatformReconView.vue'
import SettlementView from './SettlementView.vue'

const auth = useAuth()
const isPlatform = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')
</script>

<template>
  <PlatformReconView v-if="isPlatform" />
  <SettlementView v-else />
</template>
