<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M10 运营报表看板：角色化(scope) + 维度钻取(项目/批次/月) + 月趋势 + 导出。
const auth = useAuth()
const data = ref<any>(null)
const dimension = ref<'project' | 'batch' | 'month'>('batch')
const month = ref('')
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const kpiVal = (k: any) => k.kind === 'MONEY' ? yuan(k.amountCents) : k.kind === 'RATE' ? ((k.rate ?? 0) * 100).toFixed(1) + '%' : String(k.count ?? 0)
const rateOf = (r: any) => r.dueCents ? (r.repayCents / r.dueCents) * 100 : 0

async function load() {
  const q: any = { dimension: dimension.value, page: 1, size: 50 }
  if (month.value) q.month = month.value
  const { data: d, error } = await api.GET('/reports/operation', { params: { query: q } as any })
  if (error) { ElMessage.error('报表加载失败'); return }
  data.value = d
}
async function exportReport() {
  const { error } = await api.POST('/reports/export', { body: { report: 'operation', format: 'xlsx' } as any })
  if (error) { ElMessage.error('导出失败：' + ((error as any)?.message ?? '无 report.export 权限')); return }
  ElMessage.success('导出任务已提交（异步生成 xlsx，完成后下载）')
}
// 月趋势：dimension=month 时按 dimName(YYYY-MM) 排序 + 回款率条形(CSS,无图表依赖)
const trend = computed(() => dimension.value !== 'month' ? [] :
  [...(data.value?.rows ?? [])].sort((a, b) => String(a.dimName).localeCompare(String(b.dimName))))
const maxRepay = computed(() => Math.max(1, ...trend.value.map((r: any) => r.repayCents || 0)))
onMounted(load)
</script>

<template>
  <el-card v-if="data" header="运营报表（GET /reports/operation · 角色化口径 + 维度钻取 + 月趋势）">
    <div style="margin-bottom:10px;display:flex;align-items:center;gap:12px">
      <el-tag>口径：{{ data.scope }}</el-tag>
      <span>钻取维度：</span>
      <el-radio-group v-model="dimension" size="small" @change="load">
        <el-radio-button label="project">按项目</el-radio-button>
        <el-radio-button label="batch">按批次</el-radio-button>
        <el-radio-button label="month">按月份</el-radio-button>
      </el-radio-group>
      <el-date-picker v-model="month" type="month" value-format="YYYY-MM" placeholder="筛选月份(可选)" size="small" style="width:150px" clearable @change="load" />
      <el-button v-if="auth.has('report.export')" size="small" type="primary" style="margin-left:auto" @click="exportReport">导出 xlsx</el-button>
    </div>

    <el-row :gutter="12">
      <el-col v-for="k in data.kpis ?? []" :key="k.label" :span="6">
        <el-card shadow="hover" style="text-align:center">
          <div style="color:#909399;font-size:13px">{{ k.label }}</div>
          <div style="font-size:22px;font-weight:600;margin-top:6px">{{ kpiVal(k) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 月趋势(回款条形) -->
    <template v-if="dimension === 'month' && trend.length">
      <el-divider content-position="left">回款趋势（按月）</el-divider>
      <div v-for="r in trend" :key="r.dimKey" style="display:flex;align-items:center;gap:8px;margin:3px 0">
        <span style="width:70px;color:#606266;font-size:13px">{{ r.dimName }}</span>
        <div style="flex:1;background:#f0f2f5;border-radius:3px;height:18px;position:relative">
          <div :style="{ width: (r.repayCents / maxRepay * 100) + '%', background: '#409eff', height: '100%', borderRadius: '3px' }"></div>
        </div>
        <span style="width:120px;text-align:right;font-size:13px">{{ yuan(r.repayCents) }} · {{ rateOf(r).toFixed(0) }}%</span>
      </div>
    </template>

    <el-divider content-position="left">分维度明细（{{ ({project:'项目',batch:'批次',month:'月份'} as any)[dimension] }}）</el-divider>
    <el-table :data="data.rows ?? []" border size="small">
      <el-table-column prop="dimName" label="维度" />
      <el-table-column label="案件数" width="90"><template #default="{row}">{{ row.caseCount ?? '—' }}</template></el-table-column>
      <el-table-column label="应收"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents) }}</template></el-table-column>
      <el-table-column label="回款率" width="140"><template #default="{row}">
        <el-progress :percentage="Math.min(100, Math.round(rateOf(row)))" :stroke-width="12" />
      </template></el-table-column>
    </el-table>

    <template v-if="data.capabilityUsage?.length">
      <el-divider content-position="left">能力用量（计费·只量不金额 BR-M10-01）</el-divider>
      <el-table :data="data.capabilityUsage" border size="small">
        <el-table-column prop="capability" label="能力" /><el-table-column prop="used" label="用量" />
      </el-table>
    </template>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="角色化口径：物业看本物业 / 服务商看本商 / 平台看全局（服务端 scope 裁剪）。维度钻取与月趋势基于同一聚合端点。" />
  </el-card>
</template>
