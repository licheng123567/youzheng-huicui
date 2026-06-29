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
  <div v-if="data" class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>运营报表</div>
      <div class="ops"><span class="note" style="margin:0">GET /reports/operation · 角色化口径 + 维度钻取 + 月趋势</span></div>
    </div>

    <!-- 筛选/钻取工具栏 -->
    <div class="search" style="margin-bottom:16px">
      <div class="fi">
        <span>口径</span>
        <span class="tag pri">{{ data.scope }}</span>
      </div>
      <div class="fi">
        <span>钻取维度</span>
        <span class="segctrl">
          <span :class="{ on: dimension === 'project' }" @click="dimension = 'project'; load()">按项目</span>
          <span :class="{ on: dimension === 'batch' }" @click="dimension = 'batch'; load()">按批次</span>
          <span :class="{ on: dimension === 'month' }" @click="dimension = 'month'; load()">按月份</span>
        </span>
      </div>
      <div class="fi">
        <span>月份</span>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" placeholder="筛选月份(可选)" size="small" style="width:150px" clearable @change="load" />
      </div>
      <div class="fi" style="margin-left:auto">
        <button v-if="auth.has('report.export')" class="btn df" @click="exportReport">导出 xlsx</button>
      </div>
    </div>

    <!-- 关键指标 -->
    <div v-if="(data.kpis ?? []).length" class="kpis">
      <div v-for="k in data.kpis ?? []" :key="k.label" class="kpi">
        <div class="n">{{ kpiVal(k) }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
    </div>

    <!-- 月趋势(回款条形) -->
    <template v-if="dimension === 'month' && trend.length">
      <div class="sec-title">回款趋势（按月）</div>
      <div v-for="r in trend" :key="r.dimKey" class="trend-row">
        <span class="trend-lbl">{{ r.dimName }}</span>
        <div class="trend-track">
          <div class="trend-fill" :style="{ width: (r.repayCents / maxRepay * 100) + '%' }"></div>
        </div>
        <span class="trend-val num">{{ yuan(r.repayCents) }} · {{ rateOf(r).toFixed(0) }}%</span>
      </div>
    </template>

    <!-- 分维度明细 -->
    <div class="sec-title">分维度明细（{{ ({project:'项目',batch:'批次',month:'月份'} as any)[dimension] }}）</div>
    <table>
      <thead>
        <tr>
          <th>维度</th>
          <th style="width:90px">案件数</th>
          <th style="width:140px">应收</th>
          <th style="width:140px">回款</th>
          <th style="width:180px">回款率</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in data.rows ?? []" :key="row.dimKey">
          <td>{{ row.dimName || '—' }}</td>
          <td class="num">{{ row.caseCount ?? '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td class="num">{{ yuan(row.repayCents) }}</td>
          <td>
            <div class="rate-cell">
              <div class="rate-track">
                <div class="rate-fill" :style="{ width: Math.min(100, Math.round(rateOf(row))) + '%' }"></div>
              </div>
              <span class="rate-pct num">{{ Math.min(100, Math.round(rateOf(row))) }}%</span>
            </div>
          </td>
        </tr>
        <tr v-if="!(data.rows ?? []).length">
          <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <!-- 能力用量 -->
    <template v-if="data.capabilityUsage?.length">
      <div class="sec-title">能力用量（计费 · 只量不金额 BR-M10-01）</div>
      <table>
        <thead>
          <tr><th>能力</th><th style="width:160px">用量</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in data.capabilityUsage" :key="c.capability">
            <td>{{ c.capability }}</td>
            <td class="num">{{ c.used }}</td>
          </tr>
        </tbody>
      </table>
    </template>

    <div class="alert info">
      角色化口径：物业看本物业 / 服务商看本商 / 平台看全局（服务端 scope 裁剪）。维度钻取与月趋势基于同一聚合端点。
    </div>
  </div>
</template>

<style scoped>
/* 月趋势条形 */
.trend-row { display: flex; align-items: center; gap: 10px; margin: 5px 0; }
.trend-lbl { width: 70px; color: var(--reg); font-size: 13px; }
.trend-track { flex: 1; background: #f0f2f5; border-radius: 3px; height: 18px; overflow: hidden; }
.trend-fill { background: var(--primary); height: 100%; border-radius: 3px; transition: width .3s; }
.trend-val { width: 150px; text-align: right; font-size: 13px; color: var(--reg); }
/* 回款率单元格 */
.rate-cell { display: flex; align-items: center; gap: 8px; }
.rate-track { flex: 1; background: #e4e7ed; border-radius: 5px; height: 10px; overflow: hidden; }
.rate-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--teal)); border-radius: 5px; transition: width .3s; }
.rate-pct { width: 40px; text-align: right; font-size: 12px; color: var(--reg); }
</style>
