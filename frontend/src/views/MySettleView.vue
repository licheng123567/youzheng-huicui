<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 我的结算 / 佣金（催收员 CO 自查个人提成 · 只读）
// 数据源：GET /me/settlement（契约 MySettlement: totalCents/settledCents/unsettledCents/rows[]）
// 只读：比例与是否已支付由服务商内部操作，平台不参与。字段名容错以兼容后端微差。
const data = ref<any>(null)
const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(2) + '%')

// 明细行字段名容错：items 或 batches；提成 commCents 或 amountCents；回款基数 baseCents 或 repayCents。
function rowsOf(d: any): any[] {
  if (!d) return []
  return d.items ?? d.batches ?? d.rows ?? []
}
const rowBase = (r: any) => r.baseCents ?? r.repayCents
const rowComm = (r: any) => r.commCents ?? r.amountCents
const rowBatch = (r: any) => r.batch ?? r.batchName ?? r.batchId ?? '—'
const rowSettled = (r: any) => r.settled === true || r.status === 'SETTLED'

async function load() {
  loading.value = true
  const { data: d, error } = await api.GET('/me/settlement', {})
  loading.value = false
  if (error) { ElMessage.error('加载我的结算失败'); data.value = null; return }
  data.value = d
}
onMounted(load)
</script>

<template>
  <div class="card" v-loading="loading">
    <div class="card-h">
      <div class="t"><span class="bar"></span>我的结算 / 佣金</div>
      <div class="ops"><span class="note" style="margin:0">服务商内部个人提成 · 只读</span></div>
    </div>

    <div class="alert info" style="margin-top:0;margin-bottom:14px">
      <b>只读：</b>比例与是否已支付由服务商操作，平台不参与。本页仅供你自查个人提成进度。
    </div>

    <!-- 汇总三宫格 -->
    <div class="kpis" style="grid-template-columns:repeat(3,1fr)">
      <div class="kpi">
        <div class="n">{{ yuan(data?.totalCents) }}</div>
        <div class="l">累计提成</div>
      </div>
      <div class="kpi">
        <div class="n" style="color:var(--success)">{{ yuan(data?.settledCents) }}</div>
        <div class="l">已结</div>
      </div>
      <div class="kpi">
        <div class="n" style="color:var(--warning)">{{ yuan(data?.unsettledCents) }}</div>
        <div class="l">待结</div>
      </div>
    </div>

    <!-- 按批次明细 -->
    <div class="sec-title">按批次明细（GET /me/settlement）</div>
    <table>
      <thead>
        <tr>
          <th>批次号</th><th>回款基数</th><th>比例</th><th>提成</th><th style="width:90px">状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in rowsOf(data)" :key="rowBatch(row) + '-' + i">
          <td><b>{{ rowBatch(row) }}</b></td>
          <td class="num">{{ yuan(rowBase(row)) }}</td>
          <td class="num">{{ pct(row.rate) }}</td>
          <td class="num">{{ yuan(rowComm(row)) }}</td>
          <td>
            <span class="tag" :class="rowSettled(row) ? 'suc' : 'war'">
              {{ rowSettled(row) ? '已结' : '待结' }}
            </span>
          </td>
        </tr>
        <tr v-if="!loading && !rowsOf(data).length">
          <td colspan="5" class="note" style="text-align:center">暂无结算明细</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
