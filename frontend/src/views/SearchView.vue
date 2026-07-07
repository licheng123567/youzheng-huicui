<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'

// 案件/业主搜索(GET /search · BR-M4-22 · 数据范围裁剪 + 未持有公海脱敏)。
const route = useRoute(); const router = useRouter()
const q = ref(String(route.query.q ?? ''))
const items = ref<any[]>([]); const loading = ref(false); const searched = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 纯展示辅助：状态原始字符串 → 中文标签 + ds-admin .tag 配色（不影响逻辑/数据）。
const STATUS_LABEL: Record<string, string> = {
  PENDING_DISPATCH: '待派单', PROVIDER_SEA: '服务商公海', IN_PROGRESS: '催收中',
  PROMISED: '已承诺', SETTLED: '已结清', WITHDRAWN: '已撤回',
  BAD_DEBT: '坏账', VOIDED: '已作废',
}
const STATUS_TAG: Record<string, string> = {
  SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
  PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
  WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
}
const statusName = (s?: string) => STATUS_LABEL[s ?? ''] ?? s ?? '—'
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

async function run() {
  if (!q.value.trim()) return
  loading.value = true; searched.value = true
  const { data } = await api.GET('/search', { params: { query: { q: q.value.trim(), type: 'case', page: 1, size: 50 } } as any })
  loading.value = false
  items.value = (data as any)?.items ?? []
}
watch(() => route.query.q, (v) => { q.value = String(v ?? ''); run() })
onMounted(run)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>全局搜索</div>
      <div class="ops"><span class="note" style="margin:0">GET /search · 按数据范围裁剪 · 未持有公海案件脱敏</span></div>
    </div>

    <!-- 搜索栏：关键字 + 搜索按钮（v-model/事件保持不变） -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <input class="inp" v-model="q" placeholder="业主姓名/房号/户号/电话" style="min-width:300px" @keyup.enter="run" />
      </div>
      <div class="fi">
        <button class="btn" @click="run">搜索</button>
      </div>
    </div>

    <!-- 结果列表（点击行进入案件详情）。空态用末行占位。 -->
    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:120px">户号</th>
          <th style="width:110px">业主</th>
          <th style="width:100px">房号</th>
          <th>项目</th>
          <th style="width:130px">应收</th>
          <th style="width:120px">状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.caseId" class="row-click" @click="router.push(`/cases/${row.caseId}`)">
          <td>{{ row.acctNo || '—' }}</td>
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusName(row.status) }}</span></td>
        </tr>
        <tr v-if="searched && !items.length && !loading">
          <td colspan="6" style="text-align:center;color:var(--sec);padding:32px 0">无命中</td>
        </tr>
        <tr v-if="!searched && !items.length && !loading">
          <td colspan="6" style="text-align:center;color:var(--sec);padding:32px 0">输入关键字开始搜索</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
