<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M10 运营报表看板：GET /reports/operation(范围/双线聚合) → KPI 卡 + 维度行；report.export 导出。
const auth = useAuth()
const data = ref<any>(null)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
// KPI 强类型：按 kind 取值字段
const kpiVal = (k: any) => k.kind === 'MONEY' ? yuan(k.amountCents) : k.kind === 'RATE' ? ((k.rate ?? 0) * 100).toFixed(1) + '%' : String(k.count ?? 0)

async function load() {
  const { data: d, error } = await api.GET('/reports/operation', { params: { query: { page: 1, size: 20 } } as any })
  if (error) { ElMessage.error('报表加载失败'); return }
  data.value = d
}
async function exportReport() {
  const { error } = await api.POST('/reports/export', { body: { kind: 'operation' } as any })
  if (error) { ElMessage.error('导出失败：' + ((error as any)?.message ?? '无 report.export 权限')); return }
  ElMessage.success('导出任务已提交（异步生成，完成后下载）')
}
onMounted(load)
</script>

<template>
  <el-card v-if="data" header="运营报表（GET /reports/operation · 按数据范围/资金双线聚合）">
    <div style="margin-bottom:8px">
      <el-tag>{{ data.scope }}</el-tag>
      <el-button v-if="auth.has('report.export')" size="small" type="primary" style="float:right" @click="exportReport">导出报表</el-button>
    </div>
    <el-row :gutter="12">
      <el-col v-for="k in data.kpis ?? []" :key="k.label" :span="6">
        <el-card shadow="hover" style="text-align:center">
          <div style="color:#909399;font-size:13px">{{ k.label }}</div>
          <div style="font-size:22px;font-weight:600;margin-top:6px">{{ kpiVal(k) }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-divider content-position="left">分维度明细</el-divider>
    <el-table :data="data.rows ?? []" border size="small">
      <el-table-column prop="dimName" label="维度" />
      <el-table-column label="应收"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents) }}</template></el-table-column>
      <el-table-column label="回款率"><template #default="{row}">{{ row.dueCents ? ((row.repayCents/row.dueCents)*100).toFixed(1)+'%' : '—' }}</template></el-table-column>
    </el-table>
    <template v-if="data.capabilityUsage?.length">
      <el-divider content-position="left">能力用量（计费）</el-divider>
      <el-table :data="data.capabilityUsage" border size="small">
        <el-table-column prop="capability" label="能力" /><el-table-column prop="used" label="用量" />
      </el-table>
    </template>
  </el-card>
</template>
