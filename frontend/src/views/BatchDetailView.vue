<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 批次详情 /batches/:id：GET /batches/{id}(双线视角) + GET /cases?batchId 案件清单 + 减免档位。
const route = useRoute(); const router = useRouter()
const bid = String(route.params.id)
const b = ref<any>(null); const cases = ref<any[]>([]); const tiers = ref<any[]>([])
const tiersSource = ref<string | null>(null) // INHERITED | CUSTOM | null(无权限)
const tiersPermDenied = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—（视角不可见）' : (r * 100).toFixed(2) + '%')
const sourceLabel = (s: string | null) => s === 'CUSTOM' ? '批次自定义' : s === 'INHERITED' ? '继承项目默认' : ''

onMounted(async () => {
  const { data, error } = await api.GET('/batches/{id}', { params: { path: { id: bid } } })
  if (error || !data) { ElMessage.error('批次加载失败'); return }
  b.value = data
  cases.value = ((await api.GET('/cases', { params: { query: { batchId: bid, page: 1, size: 100 } } as any })).data as any)?.items ?? []
  const rt = await api.GET('/batches/{id}/reduce-tiers', { params: { path: { id: bid } } } as any)
  if ((rt.response?.status === 403) || (rt.error && (rt.error as any)?.status === 403)) {
    tiersPermDenied.value = true
  } else if (!rt.error && rt.data) {
    tiers.value = (rt.data as any)?.tiers ?? []
    tiersSource.value = (rt.data as any)?.source ?? null
  }
})
function openCase(c: any) { router.push(`/cases/${c.id}`) }
</script>

<template>
  <el-card v-if="b">
    <template #header>
      <el-button link @click="router.back()">← 返回批次</el-button>
      <span style="margin-left:8px">批次详情：{{ b.code }}（视角 {{ b.viewRole }}）</span>
    </template>
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="状态">{{ b.status }}</el-descriptions-item>
      <el-descriptions-item label="收佣比例">{{ pct(b.commInRate) }}</el-descriptions-item>
      <el-descriptions-item label="付佣比例">{{ pct(b.payOutRate) }}</el-descriptions-item>
    </el-descriptions>

    <el-divider content-position="left">
      减免档位（GET /batches/{id}/reduce-tiers）
      <el-tag v-if="tiersSource" size="small" style="margin-left:8px">{{ sourceLabel(tiersSource) }}</el-tag>
    </el-divider>
    <el-alert v-if="tiersPermDenied" type="warning" :closable="false" title="无减免策略查看权限（需 reduce.policy.edit）" style="margin-bottom:8px" />
    <el-table v-else :data="tiers" border size="small">
      <el-table-column prop="discount" label="折扣" /><el-table-column label="封顶"><template #default="{row}">{{ yuan(row.capCents) }}</template></el-table-column>
      <el-table-column prop="decide" label="决策" /><el-table-column label="免违约金"><template #default="{row}">{{ row.waivePenalty?'是':'否' }}</template></el-table-column>
    </el-table>

    <el-divider content-position="left">案件清单（GET /cases?batchId · 共 {{ cases.length }}）</el-divider>
    <el-table :data="cases" border size="small" @row-click="openCase">
      <el-table-column prop="acctNo" label="户号" /><el-table-column prop="ownerName" label="业主" />
      <el-table-column prop="room" label="房号" /><el-table-column label="应收"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" /><el-table-column prop="pool" label="池" />
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:8px" title="点案件行进作业台。资金双线：物业视角无付佣比例。" />
  </el-card>
</template>
