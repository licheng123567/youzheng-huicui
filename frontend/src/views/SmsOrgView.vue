<script setup lang="ts">
// 组织短信通道详情页 /sms/:orgId（v1.21.0·平台从短信通道列表点物业进入）。
// 内容复用 SmsOrgDetail（配置 / 模板 / 发送记录）。
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SmsOrgDetail from '../components/SmsOrgDetail.vue'

const route = useRoute()
const router = useRouter()
const orgId = computed(() => String(route.params.orgId ?? ''))
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t">
        <span class="bar"></span>
        <a class="link" @click="router.push('/sms')">短信通道</a>
        <span style="margin:0 6px;color:var(--sec)">/</span>
        组织配置
      </div>
      <div class="ops"><button class="btn sm" @click="router.push('/sms')">‹ 返回组织列表</button></div>
    </div>
    <SmsOrgDetail :org-id="orgId" />
  </div>
</template>
