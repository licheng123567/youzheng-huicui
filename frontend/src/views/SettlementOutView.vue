<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 付佣对账（OUT 线）· PL/PC 查看、VL 操作生成支付申请单
// 对齐高保真 index.html view==='reconOut'（行 891-925）
const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const isVL = computed(() => role.value === 'VL')

const rollup = ref<any[]>([])
const filter = ref({ project: '', batch: '', from: '', to: '' })

const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

async function load() {
  const { data } = await api.GET('/recon/rollup', { params: { query: { side: 'OUT', page: 1, size: 50 } } as any })
  rollup.value = (data as any)?.items ?? []
}

function resetFilter() { filter.value = { project: '', batch: '', from: '', to: '' } }

const filtered = computed(() => {
  let rows = rollup.value
  if (filter.value.project) rows = rows.filter((r: any) => (r.proj || '').includes(filter.value.project))
  if (filter.value.batch) rows = rows.filter((r: any) => (r.batch || '').includes(filter.value.batch))
  return rows
})

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>付佣对账</div>
      <div class="ops">
        <span class="note" style="margin:0">平台 → 服务商，按服务商×批次</span>
        <button class="btn df sm" @click="load()">刷新</button>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <select class="inp" v-model="filter.project" style="min-width:140px"><option value="">项目：全部</option></select>
      <select class="inp" v-model="filter.batch" style="min-width:140px"><option value="">批次：全部</option></select>
      <input class="inp" type="month" v-model="filter.from" style="min-width:140px" />
      <span class="note" style="margin:0">~</span>
      <input class="inp" type="month" v-model="filter.to" style="min-width:140px" />
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <!-- 对账汇总表 -->
    <table>
      <thead>
        <tr><th>批次</th><th>项目</th><th>回款基数</th><th>回款笔数</th><th>回款率</th><th>比例</th><th>应结</th><th>已结算</th><th>未结算</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in filtered" :key="r.batchId ?? r.batch">
          <td><b>{{ r.batch }}</b></td>
          <td>{{ r.proj }}</td>
          <td class="num">{{ yuan(r.baseCents) }}</td>
          <td class="num">{{ r.cnt || '—' }}</td>
          <td class="num">{{ r.repayRate != null ? (r.repayRate * 100).toFixed(1) + '%' : '—' }}</td>
          <td>{{ r.commRate != null ? (r.commRate * 100).toFixed(1) + '%' : '—' }}</td>
          <td class="num">{{ yuan(r.dueCents) }}</td>
          <td class="num"><span class="tag suc">{{ yuan(r.settledCents) }}</span></td>
          <td class="num"><span class="tag" :class="r.unsettledCents ? 'war' : 'inf'">{{ yuan(r.unsettledCents) }}</span></td>
          <td><a class="btn txt" @click="() => {}">回款明细</a><a class="btn txt" @click="() => {}">支付申请单</a></td>
        </tr>
        <tr v-if="!filtered.length"><td colspan="10" class="note" style="text-align:center">暂无对账数据</td></tr>
      </tbody>
    </table>

    <!-- 生成支付申请单（仅 VL 可见） -->
    <template v-if="isVL">
      <div class="sec-title" style="margin-top:14px">生成支付申请单（勾选案件回款明细 · 非按月自动）<span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">付佣线：服务商生成 → 平台付款上传凭证</span></div>
      <div class="note" style="padding:10px 0">勾选回款明细生成支付申请单功能对接后端 /payment-requests 端点。</div>
    </template>

    <div class="alert info" style="margin-top:14px">付佣线：服务商勾选案件回款明细<b>生成支付申请单</b> → 平台付款 → <b>平台上传支付凭证</b>完成。平台支付前服务商可<b>撤回</b>重生成（BR-M9-12a/b/d）。</div>
    <div class="alert info" style="margin-top:12px">物业↔服务商零资金互通：物业看不到付佣线、服务商看不到收佣线。</div>
  </div>
</template>
