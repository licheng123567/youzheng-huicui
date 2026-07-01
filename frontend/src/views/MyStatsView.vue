<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 我的统计（CO/PC）。后端无 /me/stats，拼装现有端点真值：
//  - GET /workbench → kpis[] (label/value)
//  - GET /cases?page=1&size=1 → meta.total = 可见案件数
//  - GET /recordings?page=1&size=1 → meta.total = 通话记录数
// 不编造数字：取不到的项不显示（值为 null 即隐藏）。
const loading = ref(false)
const kpis = ref<any[]>([])          // workbench.kpis
const visibleCases = ref<number | null>(null)
const recordingsTotal = ref<number | null>(null)

async function load() {
  loading.value = true
  // 三请求并发；任一失败仅该项缺省，不阻断其余。
  const results = await Promise.all([
    api.GET('/workbench', {}),
    api.GET('/cases', { params: { query: { page: 1, size: 1 } } as any }),
    api.GET('/recordings', { params: { query: { page: 1, size: 1 } } as any }),
  ])
  loading.value = false

  const wb: any = results[0]
  if (!wb.error) kpis.value = (wb.data && wb.data.kpis) ? wb.data.kpis : []

  const cs: any = results[1]
  if (!cs.error) {
    var ct = cs.data && cs.data.meta ? cs.data.meta.total : null
    visibleCases.value = (typeof ct === 'number') ? ct : null
  }

  const rc: any = results[2]
  if (!rc.error) {
    var rt = rc.data && rc.data.meta ? rc.data.meta.total : null
    recordingsTotal.value = (typeof rt === 'number') ? rt : null
  }

  if (wb.error && cs.error && rc.error) ElMessage.error('加载失败')
}

onMounted(load)
</script>

<template>
  <div class="card" v-loading="loading">
    <div class="card-h">
      <div class="t"><span class="bar"></span>我的统计</div>
      <div class="ops"><span class="note" style="margin:0">GET /workbench + /cases + /recordings</span></div>
    </div>

    <div class="kpis">
      <div class="kpi" v-for="(k, i) in kpis" :key="i">
        <div class="v">{{ k.value }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
      <div class="kpi" v-if="visibleCases !== null">
        <div class="v">{{ visibleCases }}</div>
        <div class="l">可见案件</div>
      </div>
      <div class="kpi" v-if="recordingsTotal !== null">
        <div class="v">{{ recordingsTotal }}</div>
        <div class="l">通话记录</div>
      </div>
    </div>

    <div v-if="!loading && !kpis.length && visibleCases === null && recordingsTotal === null"
         style="text-align:center;color:var(--sec);padding:32px 0">暂无可展示的统计数据</div>
  </div>

  <div class="card" style="margin-top:14px">
    <div class="note">更细粒度个人统计（评分/今日回款等）待后端 GET /me/stats。当前仅展示能取到的真值。</div>
  </div>
</template>
