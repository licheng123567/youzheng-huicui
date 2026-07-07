<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 短信通道 · 对齐高保真 index.html view==='sms'（行 1154-1197）
// 签名/模板按项目配置，PL 只读；发送统计 & 明细
const auth = useAuth()
const role = computed(() => auth.me?.role ?? '')
const isSA = computed(() => role.value === 'SA')
const isPL = computed(() => role.value === 'PL')

const items = ref<any[]>([])
const filter = ref({ from: '', to: '', status: '' })
const smsCfgProj = ref('阳光花园')

const statusTag = (s: string) => ({ SENT: 'suc', DELIVERED: 'suc', FAILED: 'dan' } as Record<string, string>)[s] ?? 'inf'
const statusName = (s: string) => ({ SENT: '成功', DELIVERED: '成功', FAILED: '失败' } as Record<string, string>)[s] ?? s

async function load() {
  const query: Record<string, any> = { page: 1, size: 100 }
  if (filter.value.from) query.from = filter.value.from
  if (filter.value.to) query.to = filter.value.to
  if (filter.value.status) query.status = filter.value.status
  const { data, error } = await api.GET('/sms-records', { params: { query } as any })
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
}

function resetFilter() { filter.value = { from: '', to: '', status: '' }; load() }

// 统计
const smsStat = computed(() => {
  const total = items.value.length
  const ok = items.value.filter((r: any) => r.status === 'SENT' || r.status === 'DELIVERED').length
  const fail = items.value.filter((r: any) => r.status === 'FAILED').length
  return { total, ok, fail }
})

// 失败原因汇总
const smsFailSummary = computed(() => {
  const map: Record<string, number> = {}
  for (const r of items.value) {
    if (r.status !== 'FAILED') continue
    const reason = r.failureReason || '未知'
    map[reason] = (map[reason] || 0) + 1
  }
  return Object.entries(map).map(([reason, count]) => ({ reason, count }))
})

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h"><div class="t"><span class="bar"></span>短信通道</div></div>

    <!-- 签名 + 模板配置（SA 可编辑，PL 只读） -->
    <div class="sec-title" style="display:flex;align-items:center;gap:8px">
      签名 + 模板配置（<b>按项目</b>）
      <span class="note" style="margin:0">项目</span>
      <select class="inp" v-model="smsCfgProj" style="min-width:140px">
        <option>阳光花园</option><option>翠湖一期</option><option>翠湖二期</option>
      </select>
      <button v-if="isSA" class="btn sm" style="margin-left:auto">保存</button>
    </div>

    <!-- SA 可编辑 -->
    <template v-if="isSA">
      <div class="desc">
        <div class="r"><div class="k">短信签名</div><div class="v"><input class="inp" style="width:220px" :value="'【'+smsCfgProj+'】'" /></div></div>
        <div class="r"><div class="k">催费模板</div><div class="v"><textarea class="inp" style="min-height:48px;width:100%">您在{小区}的物业费{金额}元待缴，缴费链接：{link}</textarea></div></div>
        <div class="r"><div class="k">冷却规则</div><div class="v">同案短信冷却 CFG-SMS-COOLDOWN（30分钟）</div></div>
      </div>
      <div class="note">短信签名/模板<b>按项目分别配置</b>（不同小区不同签名），平台维护、合规审核；物业/服务商只读。</div>
    </template>

    <!-- PL 只读 -->
    <template v-else>
      <div class="alert info" style="margin-top:0">短信签名/模板<b>按项目</b>由平台统一配置、合规审核，物业只读查看。</div>
      <div class="desc">
        <div class="r"><div class="k">短信签名</div><div class="v"><span class="tag pri">【{{ smsCfgProj }}】</span></div></div>
        <div class="r"><div class="k">催费模板</div><div class="v">您在{小区}的物业费{金额}元待缴，缴费链接：{link}</div></div>
        <div class="r"><div class="k">冷却规则</div><div class="v">同案短信冷却 CFG-SMS-COOLDOWN（30分钟）</div></div>
      </div>
    </template>

    <!-- 发送统计 & 明细 -->
    <div class="sec-title" style="display:flex;align-items:center;margin-top:18px">
      发送统计 & 明细
      <button class="btn df sm" style="margin-left:auto">导出明细</button>
    </div>

    <!-- 筛选 -->
    <div class="toolbar" style="margin-bottom:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <input class="inp" type="date" v-model="filter.from" style="min-width:150px" @change="load" />
      <span class="note" style="margin:0">~</span>
      <input class="inp" type="date" v-model="filter.to" style="min-width:150px" @change="load" />
      <select class="inp" v-model="filter.status" @change="load" style="min-width:130px">
        <option value="">状态：全部</option><option value="SENT">成功</option><option value="FAILED">失败</option>
      </select>
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <!-- KPI 统计卡 -->
    <div class="kpis" style="grid-template-columns:repeat(auto-fit,minmax(110px,1fr));margin-bottom:10px">
      <div class="kpi"><div class="n">{{ smsStat.total }}</div><div class="l">发送总数</div></div>
      <div class="kpi"><div class="n" style="color:var(--success)">{{ smsStat.ok }}</div><div class="l">成功</div></div>
      <div class="kpi"><div class="n" style="color:var(--danger)">{{ smsStat.fail }}</div><div class="l">失败</div></div>
      <div class="kpi" v-for="f in smsFailSummary" :key="f.reason">
        <div class="n" style="color:var(--danger);font-size:14px">{{ f.count }}</div>
        <div class="l">失败·{{ f.reason }}</div>
      </div>
    </div>

    <!-- 发送明细表 -->
    <table>
      <thead><tr><th>时间</th><th>案件</th><th>模板</th><th>状态</th><th>失败原因</th></tr></thead>
      <tbody>
        <tr v-for="s in items" :key="s.id || s.sentAt">
          <td>{{ s.sentAt || '—' }}</td>
          <td>{{ s.caseId || '—' }}</td>
          <td>{{ s.template || '催费模板' }}</td>
          <td><span class="tag" :class="statusTag(s.status)">{{ statusName(s.status) }}</span></td>
          <td>{{ s.status === 'FAILED' ? (s.failureReason || '未知') : '—' }}</td>
        </tr>
        <tr v-if="!items.length"><td colspan="5" class="note" style="text-align:center">所选时段无发送记录</td></tr>
      </tbody>
    </table>
    <div class="note">统计与明细按所选时段联动；同案短信冷却 CFG-SMS-COOLDOWN；失败不退条数但记原因；微信转发不扣条数。</div>
  </div>
</template>
