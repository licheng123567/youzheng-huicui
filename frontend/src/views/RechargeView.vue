<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { billingTypeLabel } from '../constants/enums'

// 充值中心 · 对齐高保真 index.html view==='recharge'（行 928-969）
// PL：仅查看余额 + 充值流水；SA：可后台充值
const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const isSA = computed(() => role.value === 'SA')
const isPL = computed(() => role.value === 'PL')

const log = ref<any[]>([])
const filter = ref({ from: '', to: '', type: '' })

// 余额卡（从 /billing/recharge-log 最新余额推算）
const balances = ref<Record<string, number>>({ STT: 120, SMS: 80, EVIDENCE: 30, LEGAL: 5 })
const usageThisMonth = ref({ stt: 0, sms: 0, evidence: 0, legal: 0 })

async function load() {
  const { data } = await api.GET('/billing/recharge-log', { params: { query: { page: 1, size: 100 } } as any })
  log.value = (data as any)?.items ?? []
  // 推余额：取最新一条 balance
  if (log.value.length) {
    const last = log.value[0]
    if (last.balance != null) balances.value.STT = last.balance
  }
}

const filteredLog = computed(() => {
  let rows = log.value
  if (filter.value.type) rows = rows.filter((r: any) => {
    const t = String(r.type || '')
    if (filter.value.type === '充值') return t.includes('充值') || t.toUpperCase().includes('RECHARGE')
    if (filter.value.type === '扣减') return t.includes('扣') || t.toUpperCase().includes('DEDUCT')
    return true
  })
  return rows
})

function resetFilter() { filter.value = { from: '', to: '', type: '' } }
function logTag(t: string) { return String(t || '').includes('充值') || t.toUpperCase().includes('RECHARGE') ? 'suc' : 'war' }

onMounted(load)
</script>

<template>
  <!-- 充值中心 · 余额总览 -->
  <div class="card">
    <div class="card-h"><div class="t"><span class="bar"></span>充值中心</div></div>
    <div class="desc">
      <div class="alert warn" style="margin-top:0;margin-bottom:8px">本阶段<b>不开放自助充值</b>：分钟 / 短信 / 存证 / 法律服务 次数均<b>由平台后台充值</b>；下方为余额与本月用量。</div>

      <!-- 通话解析分钟数 -->
      <div class="r">
        <div class="k">通话解析分钟数</div>
        <div class="v">
          <span class="tag" :class="balances.STT < 120 ? 'war' : 'suc'">余额 {{ balances.STT }} 分钟<span v-if="balances.STT < 120"> ⚠低于预警</span></span>
          <button v-if="isSA" class="btn sm" style="margin-left:8px">后台充值</button>
          <span v-else class="note" style="margin-left:8px">由平台后台充值</span>
        </div>
      </div>

      <!-- 短信条数（仅物业） -->
      <div class="r" v-if="isPL || isSA">
        <div class="k">短信条数（仅物业）</div>
        <div class="v">
          <span class="tag pri">余额 {{ balances.SMS }} 条</span>
          <button v-if="isSA" class="btn sm" style="margin-left:8px">后台充值</button>
          <span v-else class="note" style="margin-left:8px">由平台后台充值</span>
        </div>
      </div>

      <!-- 存证次数（仅物业） -->
      <div class="r" v-if="isPL || isSA">
        <div class="k">存证次数（仅物业）</div>
        <div class="v">
          <span class="tag suc">余额 {{ balances.EVIDENCE }} 次</span>
          <span class="note" style="margin-left:8px">本月已用 {{ usageThisMonth.evidence }} 次</span>
          <button v-if="isSA" class="btn sm" style="margin-left:8px">后台充值</button>
          <span v-else class="note" style="margin-left:8px">由平台后台充值</span>
        </div>
      </div>

      <!-- 法律服务次数（仅物业） -->
      <div class="r" v-if="isPL || isSA">
        <div class="k">法律服务次数（仅物业）</div>
        <div class="v">
          <span class="tag war">余额 {{ balances.LEGAL }} 次</span>
          <span class="note" style="margin-left:8px">本月已用 {{ usageThisMonth.legal }} 次</span>
          <button v-if="isSA" class="btn sm" style="margin-left:8px">后台充值</button>
          <span v-else class="note" style="margin-left:8px">由平台后台充值</span>
        </div>
      </div>
    </div>
    <div class="note">打电话/录音不耗分钟，上传解析才扣；存证按次、法律服务按次计入对账。</div>
  </div>

  <!-- 充值 / 扣减流水 -->
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>充值 / 扣减流水</div>
      <div class="ops"><button class="btn df sm">导出</button></div>
    </div>
    <div class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <input class="inp" type="date" v-model="filter.from" style="min-width:140px" />
      <span class="note" style="margin:0">~</span>
      <input class="inp" type="date" v-model="filter.to" style="min-width:140px" />
      <select class="inp" v-model="filter.type" style="min-width:120px">
        <option value="">类型：全部</option><option>充值</option><option>扣减</option>
      </select>
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>
    <table>
      <thead><tr><th>时间</th><th>类型</th><th>数量</th><th>余额</th><th>关联</th></tr></thead>
      <tbody>
        <tr v-for="(g, i) in filteredLog" :key="i">
          <td>{{ g.tm || '—' }}</td>
          <td><span class="tag" :class="logTag(g.type)">{{ billingTypeLabel(g.type) }}</span></td>
          <td class="num">{{ g.delta != null ? g.delta : '—' }}</td>
          <td class="num">{{ g.balance != null ? g.balance : '—' }}</td>
          <td>{{ g.ref || '—' }}</td>
        </tr>
        <tr v-if="!filteredLog.length"><td colspan="5" class="note" style="text-align:center">暂无流水</td></tr>
      </tbody>
    </table>
    <div class="note">打电话/录音不耗分钟，上传解析才扣；存证按次、法律服务按次计入对账。</div>
  </div>

  <!-- 待解析录音 -->
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>待解析录音</div>
      <div class="ops"><button class="btn sm">批量补解析</button></div>
    </div>
    <div class="alert warn" style="margin-top:0">分钟余额≤阈值时自动解析暂停，充值后可手动批量补解析。</div>
    <table>
      <thead><tr><th>录音时间</th><th>时长</th><th>关联案件</th><th>状态</th></tr></thead>
      <tbody>
        <tr><td colspan="4" class="note" style="text-align:center">暂无待解析录音</td></tr>
      </tbody>
    </table>
  </div>
</template>
