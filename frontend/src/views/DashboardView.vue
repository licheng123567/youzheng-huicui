<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

// 工作台：当前主体 + 角色相关待办计数 + 快捷入口（聚合现有列表端点，无专用 todo 端点）。
const auth = useAuth()
const router = useRouter()
const me = computed(() => auth.me)
const stat = ref<Record<string, number>>({})

async function count(path: string, query: any): Promise<number> {
  const { data } = await api.GET(path as any, { params: { query } as any })
  const d: any = data
  return d?.meta?.total ?? d?.items?.length ?? (Array.isArray(d) ? d.length : 0)
}
onMounted(async () => {
  // 角色相关待办（容错：无权限端点返 403→计 0）
  if (auth.has('case.claim')) stat.value['公海待抢'] = await count('/sea', { pool: 'provider', page: 1, size: 1 }).catch(() => 0)
  if (auth.has('case.follow') || auth.has('case.claim')) stat.value['我的案件'] = await count('/cases', { page: 1, size: 1 }).catch(() => 0)
  if (auth.has('qc.review')) stat.value['待复核风险'] = await count('/risks', { page: 1, size: 1 }).catch(() => 0)
  if (auth.has('qc.dispose')) stat.value['待处置风险'] = await count('/risks', { page: 1, size: 1 }).catch(() => 0)
  if (auth.has('payreq.complete') || auth.has('payreq.create')) stat.value['支付申请单'] = await count('/payment-requests', { side: auth.has('payreq.complete') ? 'IN' : 'OUT', page: 1, size: 1 }).catch(() => 0)
  if (auth.has('member.manage')) stat.value['本组织成员'] = await count('/members', { page: 1, size: 1 }).catch(() => 0)
})
const links = computed(() => {
  const l: { label: string; path: string }[] = []
  if (auth.has('case.claim')) l.push({ label: '去公海抢单', path: '/sea' })
  if (auth.has('case.dispatch') || auth.has('batch.import')) l.push({ label: '批次派单', path: '/batches' })
  l.push({ label: '案件列表', path: '/cases' })
  if (auth.has('payreq.create') || auth.has('payreq.complete') || auth.has('cocomm.self.view')) l.push({ label: '结算', path: '/settlement' })
  if (auth.has('qc.dispose') || auth.has('qc.review')) l.push({ label: '质检', path: '/risks' })
  return l
})
</script>

<template>
  <div v-if="me">
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col v-for="(v, k) in stat" :key="k" :span="4">
        <el-card shadow="hover" style="text-align:center">
          <div style="color:#909399;font-size:13px">{{ k }}</div>
          <div style="font-size:24px;font-weight:600;margin-top:4px">{{ v }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card header="快捷入口" style="margin-bottom:12px">
      <el-button v-for="ln in links" :key="ln.path" type="primary" plain @click="router.push(ln.path)">{{ ln.label }}</el-button>
    </el-card>

    <el-card header="当前主体（契约 GET /me）">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="账号 ID">{{ me.accountId }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ me.name }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ me.role }}</el-descriptions-item>
        <el-descriptions-item label="组织">{{ me.org?.name }}（{{ me.org?.type }}）</el-descriptions-item>
        <el-descriptions-item label="数据范围" :span="2">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量（dataScope=null）' }}</el-descriptions-item>
        <el-descriptions-item label="权限点" :span="2"><el-tag v-for="p in me.permissions" :key="p" style="margin:2px">{{ p }}</el-tag></el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
  <el-empty v-else description="加载主体中…" />
</template>
