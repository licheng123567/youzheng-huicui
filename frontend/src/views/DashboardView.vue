<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

// 角色工作台(GET /workbench · BR-M4-20/20a)：CO/PC=今日驾驶舱(待办列表+KPI可点筛)；管理角色=仪表盘。
const auth = useAuth()
const router = useRouter()
const me = computed(() => auth.me)
const wb = ref<any>(null)
const filterKey = ref<string>('')   // KPI 点击过滤

const CAT_LABEL: Record<string, string> = {
  PROMISE_DUE: '承诺到期', RELEASE_WARN: '临近释放', TICKET_RECEIPT: '工单回执',
  NEW_ASSIGNED: '新分配', LEGAL_DELIVERY: '法务待送达', REPAY_MARK: '回款待标',
  PAYLINK_SEND: '链接待发', REDUCE_APPROVE: '减免待批',
}
const urgType = (u: string) => (u === 'HIGH' ? 'danger' : u === 'MED' ? 'warning' : 'info')
const todos = computed<any[]>(() => {
  const list = wb.value?.todos ?? []
  return filterKey.value ? list.filter((t: any) => t.category === filterKey.value) : list
})

async function load() {
  const { data } = await api.GET('/workbench', {})
  wb.value = data
}
function openTodo(t: any) { if (t.caseId) router.push(`/cases/${t.caseId}`) }
onMounted(load)
</script>

<template>
  <div v-if="me">
    <!-- KPI（可点即筛 BR-M4-20a） -->
    <el-row v-if="wb?.kpis?.length" :gutter="12" style="margin-bottom:12px">
      <el-col v-for="k in wb.kpis" :key="k.label" :span="4">
        <el-card shadow="hover" :body-style="{ cursor: k.filterKey ? 'pointer' : 'default', textAlign: 'center' }"
          @click="k.filterKey && (filterKey = filterKey === k.filterKey ? '' : k.filterKey)">
          <div style="color:#909399;font-size:13px">{{ k.label }}</div>
          <div style="font-size:24px;font-weight:600;margin-top:4px" :style="{ color: filterKey === k.filterKey ? '#409eff' : '' }">{{ k.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 今日驾驶舱：待办列表（cockpit·CO/PC） -->
    <el-card v-if="wb?.layout === 'cockpit'" style="margin-bottom:12px">
      <template #header>
        <span>今日必办</span>
        <el-button v-if="filterKey" text size="small" style="margin-left:8px" @click="filterKey = ''">清除筛选（{{ CAT_LABEL[filterKey] || filterKey }}）</el-button>
      </template>
      <el-empty v-if="!todos.length" description="暂无待办（承诺/释放/工单回执已清空）" :image-size="60" />
      <div v-for="(t, i) in todos" :key="i" style="display:flex;align-items:center;gap:10px;padding:8px 4px;border-bottom:1px solid #f0f2f5;cursor:pointer" @click="openTodo(t)">
        <span :style="{ width: '4px', alignSelf: 'stretch', background: t.urgency === 'HIGH' ? '#f56c6c' : t.urgency === 'MED' ? '#e6a23c' : '#909399', borderRadius: '2px' }"></span>
        <el-tag size="small" :type="urgType(t.urgency)">{{ CAT_LABEL[t.category] || t.category }}</el-tag>
        <span style="flex:1">{{ t.title }}</span>
        <span v-if="t.deadline" style="color:#909399;font-size:12px">{{ String(t.deadline).slice(0, 16).replace('T', ' ') }}</span>
        <el-button v-if="t.caseId" size="small" text type="primary">进案件 →</el-button>
      </div>
    </el-card>

    <!-- 当前主体 -->
    <el-card header="当前主体（契约 GET /me）">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="账号 ID">{{ me.accountId }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ me.name }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ me.role }}（工作台 {{ wb?.layout === 'cockpit' ? '今日驾驶舱' : '仪表盘' }}）</el-descriptions-item>
        <el-descriptions-item label="组织">{{ me.org?.name }}（{{ me.org?.type }}）</el-descriptions-item>
        <el-descriptions-item label="数据范围" :span="2">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量（dataScope=null）' }}</el-descriptions-item>
        <el-descriptions-item label="权限点" :span="2"><el-tag v-for="p in me.permissions" :key="p" style="margin:2px">{{ p }}</el-tag></el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
  <el-empty v-else description="加载主体中…" />
</template>
