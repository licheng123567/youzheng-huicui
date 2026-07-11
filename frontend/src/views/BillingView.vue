<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 计费明细 · 对齐高保真 index.html view==='billing'（行 971-1007）
// 月→日→明细 三级下钻；仅记录用量不展示金额
const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const isPL = computed(() => role.value === 'PL')

const usage = ref<any[]>([])
const filter = ref({ project: '', batch: '', type: '', from: '', to: '' })
const drillMonth = ref('')   // '' = 月汇总; 有值 = 按日
const drillDay = ref('')     // '' = 按日; 有值 = 明细

const USAGE_CN: Record<string, string> = { STT: 'STT解析', SMS: '短信', EVIDENCE: '存证', LEGAL: '法律服务' }
// 计费明细只入账成功计费（失败不计费 BR-M6-06），故状态列恒「成功」，无重试。

async function load() {
  const { data } = await api.GET('/billing/usage', { params: { query: { page: 1, size: 200 } } as any })
  usage.value = (data as any)?.items ?? []
}

function resetFilter() { filter.value = { project: '', batch: '', type: '', from: '', to: '' } }

// ── 月汇总（前端聚合） ──
const billMonths = computed(() => {
  const byMonth: Record<string, any> = {}
  for (const it of usage.value) {
    const ym = String(it.occurredAt || '').slice(0, 7)
    if (!ym) continue
    if (!byMonth[ym]) byMonth[ym] = { mo: ym, stt: 0, sms: 0, ev: 0, legal: 0, cnt: 0 }
    const m = byMonth[ym]; m.cnt++
    const t = String(it.type || '').toUpperCase()
    if (t === 'STT') m.stt += Number(it.qty) || 0
    else if (t === 'SMS') m.sms += Number(it.qty) || 0
    else if (t === 'EVIDENCE') m.ev += Number(it.qty) || 0
    else if (t === 'LEGAL') m.legal += Number(it.qty) || 0
  }
  return Object.values(byMonth).sort((a: any, b: any) => (a.mo < b.mo ? 1 : -1))
})

// ── 按日（当前 drillMonth 下的日聚合） ──
const billDays = computed(() => {
  if (!drillMonth.value) return []
  const byDay: Record<string, any> = {}
  for (const it of usage.value) {
    const d = String(it.occurredAt || '').slice(0, 10)
    const ym = d.slice(0, 7)
    if (ym !== drillMonth.value) continue
    if (!byDay[d]) byDay[d] = { date: d, stt: 0, sms: 0, ev: 0, legal: 0, cnt: 0 }
    const r = byDay[d]; r.cnt++
    const t = String(it.type || '').toUpperCase()
    if (t === 'STT') r.stt += Number(it.qty) || 0
    else if (t === 'SMS') r.sms += Number(it.qty) || 0
    else if (t === 'EVIDENCE') r.ev += Number(it.qty) || 0
    else if (t === 'LEGAL') r.legal += Number(it.qty) || 0
  }
  return Object.values(byDay).sort((a: any, b: any) => (a.date < b.date ? 1 : -1))
})

// ── 明细（当前 drillDay 下的原始行） ──
const billDetail = computed(() => {
  if (!drillDay.value) return []
  return usage.value.filter((it: any) => String(it.occurredAt || '').slice(0, 10) === drillDay.value)
})

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>计费明细</div>
      <div class="ops">
        <span class="note" style="margin:0">仅记录能力<b>用量</b>（STT/短信/存证/法务），不展示金额</span>
        <button class="btn df sm">导出</button>
      </div>
    </div>

    <!-- PL 筛选：项目/批次/类型/日期 -->
    <div v-if="isPL" class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <select class="inp" v-model="filter.project" style="min-width:130px"><option value="">项目：全部</option></select>
      <select class="inp" v-model="filter.batch" style="min-width:130px"><option value="">批次：全部</option></select>
      <select class="inp" v-model="filter.type" style="min-width:130px"><option value="">类型：全部</option><option>STT解析</option><option>存证</option><option>法律服务</option><option>短信</option></select>
      <input class="inp" type="date" v-model="filter.from" style="min-width:140px" /><span class="note" style="margin:0">~</span>
      <input class="inp" type="date" v-model="filter.to" style="min-width:140px" />
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <!-- 月汇总（一级） -->
    <template v-if="!drillMonth">
      <table>
        <thead><tr><th>月份</th><th>STT</th><th>短信</th><th>存证</th><th>法务</th><th>笔数</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in billMonths" :key="m.mo">
            <td><b>{{ m.mo }}</b></td>
            <td class="num">{{ m.stt || '—' }}</td>
            <td class="num">{{ m.sms || '—' }}</td>
            <td class="num">{{ m.ev || '—' }}</td>
            <td class="num">{{ m.legal || '—' }}</td>
            <td class="num">{{ m.cnt }}</td>
            <td><a class="btn txt" @click="drillMonth = m.mo">按日查看 ›</a></td>
          </tr>
          <tr v-if="!billMonths.length"><td colspan="7" class="note" style="text-align:center">无计费用量记录</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 按日（二级） -->
    <template v-else-if="!drillDay">
      <div class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center">
        <button class="btn df sm" @click="drillMonth = ''">← 返回月汇总</button>
        <span class="note" style="margin:0">{{ drillMonth }} · 按日</span>
      </div>
      <table>
        <thead><tr><th>日期</th><th>STT</th><th>短信</th><th>存证</th><th>法务</th><th>笔数</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="d in billDays" :key="d.date">
            <td>{{ d.date }}</td>
            <td class="num">{{ d.stt || '—' }}</td><td class="num">{{ d.sms || '—' }}</td>
            <td class="num">{{ d.ev || '—' }}</td><td class="num">{{ d.legal || '—' }}</td>
            <td class="num">{{ d.cnt }}</td>
            <td><a class="btn txt" @click="drillDay = d.date">查看明细 ›</a></td>
          </tr>
        </tbody>
      </table>
    </template>

    <!-- 明细（三级） -->
    <template v-else>
      <div class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center">
        <button class="btn df sm" @click="drillDay = ''">← 返回按日</button>
        <span class="note" style="margin:0">{{ drillDay }} · 明细</span>
      </div>
      <table>
        <thead><tr><th>日期</th><th>类型</th><th>用量/次</th><th>业主</th><th>房号</th><th>项目</th><th>批次</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="b in billDetail" :key="b.id || b.occurredAt">
            <td>{{ String(b.occurredAt || '').slice(0, 10) }}</td>
            <td><span class="tag" :class="String(b.type||'').toUpperCase()==='STT'?'pri':'inf'">{{ USAGE_CN[String(b.type||'').toUpperCase()] || b.type || '—' }}</span></td>
            <td class="num">{{ b.qty || '—' }}</td>
            <td>{{ b.ownerName || '—' }}</td><td>{{ b.room || '—' }}</td>
            <td>{{ b.projectName || '—' }}</td><td>{{ b.batchNo || '—' }}</td>
            <td><span class="tag suc">成功</span></td>
          </tr>
          <tr v-if="!billDetail.length"><td colspan="8" class="note" style="text-align:center">无明细数据</td></tr>
        </tbody>
      </table>
    </template>

    <div class="note" style="margin-top:8px">先按月统计 → 点「按日查看」→ 点「查看明细」看每笔（含业主/房号）。STT 按分钟、存证/法务按次、短信按条；<b>失败不计费</b>，可重试（M6-06）。</div>
  </div>
</template>
