<script setup lang="ts">
// 「额度管理」（v1.19.0）：计费明细 + 充值中心合并页，以**组织**为聚合。
//   平台(SA/SE)：**先看组织列表**（一行一个组织，四类额度横向展开）→ 点组织进 /quota/:orgId 看其
//     用量/充值明细。SA 可充值；SE 只读（无 billing.recharge，按钮禁用带 tooltip）。
//   物业/服务商(PL/VL)：本页直接渲染自己的组织详情（range scope 天然裁剪，无组织列表这层）。
// 余额权威源=org_balance(V932)；EVIDENCE 后付费，余额可为负（欠用记账）；法律文书不计费(v1.20.0)。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import QuotaOrgDetail from '../components/QuotaOrgDetail.vue'

const auth = useAuth()
const router = useRouter()
const isPlatform = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')

const TYPE_ORDER = ['STT', 'SMS', 'EVIDENCE']
// v1.20.0：法律文书不计费（无「法律文书余额」概念）→ 额度体系只剩三类
const TYPE_LABEL: Record<string, string> = { STT: '录音转写', SMS: '短信', EVIDENCE: '存证' }
const fmtQty = (v?: number | null, unit?: string | null) =>
  v == null ? '—' : (Number(v.toFixed(3)).toLocaleString('zh-CN') + (unit ?? ''))

// ── 组织列表（平台）：GET /billing/orgs 返回扁平「组织×类型」行 → 按组织归并为一行一组织 ──
const rows = ref<any[]>([]); const loading = ref(false)
async function loadOrgs() {
  loading.value = true
  const { data, error } = await api.GET('/billing/orgs', { params: { query: { page: 1, size: 200 } } as any })
  loading.value = false
  if (error) { ElMessage.error('加载组织额度失败'); rows.value = []; return }
  const items = (data as any)?.items ?? []
  const byOrg = new Map<string, any>()
  for (const it of items) {
    const key = String(it.orgId)
    if (!byOrg.has(key)) {
      byOrg.set(key, { orgId: it.orgId, orgName: it.orgName, orgType: it.orgType, q: {} as Record<string, any>, usedThisMonth: 0, owing: false })
    }
    const o = byOrg.get(key)
    o.q[it.type] = it
    o.usedThisMonth += it.usedThisMonth ?? 0
    if ((it.balance ?? 0) < 0) o.owing = true      // 任一后付费类型欠用 → 行标记
  }
  rows.value = [...byOrg.values()]
}

function openOrg(row: any) { router.push(`/quota/${row.orgId}`) }

onMounted(() => { if (isPlatform.value) loadOrgs() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>额度管理</div>
      <div class="ops">
        <span class="note" style="margin:0">能力额度：录音转写/短信（预付·需充值）· 存证（后付·按次计入对账）</span>
      </div>
    </div>

    <!-- 平台：组织列表（一行一组织，四类额度横向展开）→ 点行进详情 -->
    <template v-if="isPlatform">
      <div class="note" style="margin-bottom:10px">点击组织查看其用量明细与充值流水；余额为负（红色·欠用）表示后付费类型的欠用记账。</div>
      <table v-loading="loading">
        <thead>
          <tr>
            <th>组织</th>
            <th style="width:80px">类型</th>
            <th v-for="t in TYPE_ORDER" :key="t" style="width:120px">{{ TYPE_LABEL[t] }}余额</th>
            <th style="width:110px">本月总用量</th>
            <th style="width:90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.orgId" class="row-click" @click="openOrg(row)">
            <td><b>{{ row.orgName }}</b><span v-if="row.owing" class="tag dan" style="margin-left:6px">欠用</span></td>
            <td><span class="tag inf">{{ row.orgType === 'PROPERTY' ? '物业' : '服务商' }}</span></td>
            <td v-for="t in TYPE_ORDER" :key="t" class="num">
              <span v-if="row.q[t]" :class="(row.q[t].balance ?? 0) < 0 ? 'tag dan' : ''">
                {{ fmtQty(row.q[t].balance, row.q[t].unit) }}
              </span>
              <span v-else>—</span>
            </td>
            <td class="num">{{ row.usedThisMonth ? Number(row.usedThisMonth.toFixed(3)).toLocaleString('zh-CN') : 0 }}</td>
            <td @click.stop><a class="btn txt" @click="openOrg(row)">明细 ›</a></td>
          </tr>
          <tr v-if="!loading && !rows.length"><td :colspan="TYPE_ORDER.length + 4" class="note" style="text-align:center;padding:32px 0">暂无组织额度数据</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 物业/服务商：直接看自己（无组织列表这层） -->
    <QuotaOrgDetail v-else :org-id="null" />
  </div>
</template>

<style scoped>
.row-click { cursor: pointer; }
.row-click:hover { background: var(--bg2, #f7f9fc); }
</style>
