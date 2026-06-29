<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'
import { permLabel } from '../constants/permissions'
import { orgTypeLabel, caseStatusLabel, activityTypeLabel } from '../constants/enums'

// 角色工作台(GET /workbench · BR-M4-20/20a)：CO/PC=今日驾驶舱(待办列表+KPI可点筛)；管理角色=仪表盘。
const auth = useAuth()
const router = useRouter()
const me = computed(() => auth.me)
const wb = ref<any>(null)
const filterKey = ref<string>('')   // KPI 点击过滤

const CAT_LABEL: Record<string, string> = {
  PROMISE_DUE: '承诺到期', RELEASE_WARN: '临近释放', TICKET_RECEIPT: '工单回执',
  NEW_ASSIGNED: '新分配', LEGAL_DELIVERY: '法务待送达', REPAY_MARK: '回款待标',
  PAYLINK_SEND: '链接待发', REDUCE_APPROVE: '减免待批',
  T2_RETURN_WARN: '即将退回平台', T1_DISPATCH_WARN: '待派单超时',
}
const urgType = (u: string) => (u === 'HIGH' ? 'danger' : u === 'MED' ? 'warning' : 'info')
// 纯展示：紧急度 → ds-admin 配色级别（色条/标签/ck-chip 复用）
const urgLv = (u: string) => (u === 'HIGH' ? 'dan' : u === 'MED' ? 'war' : 'inf')
const urgTag = (u: string) => (u === 'HIGH' ? 'dan' : u === 'MED' ? 'war' : 'inf')
const todos = computed<any[]>(() => {
  const list = wb.value?.todos ?? []
  return filterKey.value ? list.filter((t: any) => t.category === filterKey.value) : list
})

async function load() {
  const { data } = await api.GET('/workbench', {})
  wb.value = data
}
function openTodo(t: any) { if (t.caseId) router.push(`/cases/${t.caseId}`) }
onMounted(load)

// ===== cockpit master-detail（仅新增「读取/选中」型逻辑，不改写操作）=====
// 选中态：本地 ref；与 KPI 筛选(filterKey)、tab 解耦
const cockpitId = ref<string>('')
const cpDetail = ref<any>(null)         // GET /cases/{id} → { case, timeline, ... }
const cpLoading = ref(false)

// 时间线类型 → 颜色（与案件页一致；缺省 inf）
const tlTag = (t?: string) => {
  const k = String(t || '').toUpperCase()
  if (k === 'CALL') return 'pri'
  if (k === 'PROMISE' || k === 'REPAY') return 'suc'
  if (k === 'TICKET' || k === 'LEGAL') return 'war'
  return 'inf'
}
const caseStatusTag = (s?: string) => {
  const m: Record<string, string> = {
    SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war',
    PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf',
    WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan',
  }
  return m[s ?? ''] ?? 'inf'
}
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 左 worklist：复用 filterKey 过滤后的 todos（todo 字段不足时优雅降级）
const worklist = computed<any[]>(() => todos.value)
// 概览：紧急（HIGH）提醒，取全量 todos（不受 filterKey 限制）
const allTodos = computed<any[]>(() => wb.value?.todos ?? [])
const worklistUrgent = computed<any[]>(() => allTodos.value.filter((t: any) => t.urgency === 'HIGH').slice(0, 6))

// wl-tabs：由 todos 的 category 分布派生（全部 + 各分类计数），点击复用 filterKey
const wlTabs = computed<Array<{ k: string; l: string; n: number }>>(() => {
  const list = allTodos.value
  const counts: Record<string, number> = {}
  for (const t of list) counts[t.category] = (counts[t.category] || 0) + 1
  const tabs: Array<{ k: string; l: string; n: number }> = [{ k: '', l: '全部', n: list.length }]
  for (const cat of Object.keys(counts)) tabs.push({ k: cat, l: CAT_LABEL[cat] || cat, n: counts[cat] })
  return tabs
})

// 今日概览进度（演示态：已选/进入过的视为已处理参考；基于 todos 总量给出剩余）
const ovTotal = computed<number>(() => allTodos.value.length || 0)
const ovRemain = computed<number>(() => allTodos.value.length)
const ovPct = computed<number>(() => 0) // 无「已处理」数据来源，进度条以剩余件数为主语义（保守置 0）

const cpCase = computed<any>(() => cpDetail.value?.case ?? null)
const cpTimeline = computed<any[]>(() => (cpDetail.value?.timeline ?? []).slice(0, 6))
const cpInitial = computed<string>(() => {
  const n = cpCase.value?.ownerName
  return n ? String(n).charAt(0) : '案'
})
// 选中 todo 标题（无 case 字段时降级展示）
const cpTitle = computed<string>(() => {
  const t = (allTodos.value.find((x: any) => x.caseId === cockpitId.value))
  return t?.title || cockpitId.value
})

async function selectWl(caseId?: string) {
  if (!caseId) return
  cockpitId.value = caseId
  cpDetail.value = null
  cpLoading.value = true
  try {
    const { data } = await api.GET('/cases/{id}', { params: { path: { id: caseId } } })
    cpDetail.value = data
  } finally {
    cpLoading.value = false
  }
}
function fullScreen() { if (cockpitId.value) router.push(`/cases/${cockpitId.value}`) }
</script>

<template>
  <div v-if="me">
    <!-- ① KPI 可点即筛选条（BR-M4-20a · 驾驶舱 ck-chip） -->
    <div v-if="wb?.kpis?.length" class="cockpit-kpis">
      <div
        v-for="k in wb.kpis"
        :key="k.label"
        class="ck-chip"
        :class="{ on: filterKey === k.filterKey, ro: !k.filterKey }"
        @click="k.filterKey && (filterKey = filterKey === k.filterKey ? '' : k.filterKey)"
      >
        <div class="n">{{ k.value }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
    </div>

    <!-- ② 今日驾驶舱（cockpit · CO/PC）：master-detail = 左今日必办 worklist + 右选中案件预览 -->
    <div v-if="wb?.layout === 'cockpit'" class="cockpit">
      <!-- 左：今日必办 worklist（tab 过滤 + 紧急度色条） -->
      <div class="wl">
        <div class="wl-tabs">
          <span
            v-for="t in wlTabs"
            :key="t.k"
            class="wl-tab"
            :class="{ on: filterKey === t.k }"
            @click="filterKey = t.k"
          >{{ t.l }}<b>{{ t.n }}</b></span>
        </div>
        <div class="wl-list">
          <div
            v-for="(it, i) in worklist"
            :key="it.caseId || i"
            class="wl-item"
            :class="[urgLv(it.urgency), { on: cockpitId && cockpitId === it.caseId }]"
            @click="it.caseId ? selectWl(it.caseId) : openTodo(it)"
          >
            <div class="bar2"></div>
            <div class="wl-main">
              <div class="r1">
                <!-- 降级：todo 无 name/room/due，用 title 兜底；deadline → 截止 -->
                <span class="nm">{{ it.title }}</span>
                <span v-if="it.deadline" class="amt" style="font-weight:400;color:var(--sec);font-size:12px">
                  {{ String(it.deadline).slice(0, 16).replace('T', ' ') }}
                </span>
              </div>
              <div class="r2">
                <span class="tag" :class="urgTag(it.urgency)">{{ CAT_LABEL[it.category] || it.category }}</span>
                <span class="sla" :class="urgLv(it.urgency)">
                  {{ it.caseId ? '预览 →' : '无关联案件' }}
                </span>
              </div>
            </div>
          </div>
          <div v-if="!worklist.length" class="wl-empty">该分类下暂无待办 🎉</div>
        </div>
      </div>

      <!-- 右：选中→案件预览（一键进三栏）；未选→今日概览 -->
      <div class="cp-detail" :class="{ embed: cockpitId }">
        <!-- 已选：轻量案件预览（GET /cases/{id} 取 case/timeline） -->
        <div v-if="cockpitId" class="cp-embed">
          <div class="cp-embed-h">
            <div class="t">
              {{ cpCase?.ownerName || cpTitle }}
              <template v-if="cpCase?.room"> · {{ cpCase.room }}</template>
              <span v-if="cpCase?.status" class="tag" :class="caseStatusTag(cpCase.status)" :title="cpCase.status">{{ caseStatusLabel(cpCase.status) }}</span>
            </div>
            <div class="ops">
              <button class="btn pl sm" @click="fullScreen()">全屏 ⤢</button>
            </div>
          </div>

          <div v-if="cpLoading" class="note" style="padding:24px 0;text-align:center">加载案件预览中…</div>
          <template v-else-if="cpDetail">
            <!-- 画像 -->
            <div class="portrait-top" style="margin-bottom:14px">
              <div class="portrait-av" style="background:var(--primary);width:46px;height:46px;border-radius:50%;color:#fff;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:600;flex:none">{{ cpInitial }}</div>
              <div class="portrait-id" style="flex:1">
                <div class="nm">{{ cpCase?.ownerName || '—' }}</div>
                <div class="sub">{{ cpCase?.room || '—' }} · 户号 {{ cpCase?.acctNo || '—' }}</div>
              </div>
              <div style="text-align:right">
                <div class="num" style="font-size:20px;font-weight:700;color:var(--danger)">{{ yuan(cpCase?.dueCents) }}</div>
                <div class="note" style="margin:0">应收欠费</div>
              </div>
            </div>
            <div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px">
              <span v-if="cpCase?.pool" class="tag inf">{{ cpCase.pool }}</span>
              <span v-if="cpCase?.redacted" class="tag inf">已脱敏</span>
              <span v-if="cpCase?.reduceAfterCents != null && cpCase.reduceAfterCents !== cpCase.dueCents" class="tag war">减免后 {{ yuan(cpCase.reduceAfterCents) }}</span>
            </div>

            <!-- 最近时间线（轻量预览） -->
            <div class="sec-title">最近动态</div>
            <div class="tl" v-if="cpTimeline.length">
              <div class="e" v-for="ev in cpTimeline" :key="ev.id">
                <span class="tag" :class="tlTag(ev.type)" :title="ev.type">{{ activityTypeLabel(ev.type) }}</span>
                {{ ev.content }}
                <b style="float:right;color:var(--ph);font-weight:400">{{ String(ev.createdAt || '').slice(0, 16).replace('T', ' ') }}</b>
              </div>
            </div>
            <div v-else class="note">暂无事件。</div>

            <button class="btn pl cp-enter" style="width:100%;margin-top:18px" @click="fullScreen()">进案件作业 →</button>
          </template>
          <div v-else class="note" style="padding:24px 0;text-align:center">案件预览加载失败，可点「全屏 ⤢」进入。</div>
        </div>

        <!-- 未选：今日概览 -->
        <div v-else class="cp-overview">
          <div class="ov-h">今日概览 · {{ me.name }}</div>
          <div class="ov-progress">
            <div class="ov-bar"><div class="ov-fill" :style="{ width: ovPct + '%' }"></div></div>
            <div class="ov-txt">今日待办 <b>{{ ovTotal }}</b> 件 · 剩余 {{ ovRemain }} 件待处理</div>
          </div>
          <div class="sec-title">重点提醒（紧急）</div>
          <div class="tl">
            <div class="e" v-for="it in worklistUrgent" :key="it.caseId || it.title" @click="selectWl(it.caseId)">
              <span class="tag" :class="urgTag(it.urgency)">{{ CAT_LABEL[it.category] || it.category }}</span>
              {{ it.title }}
              <b v-if="it.deadline" style="float:right">{{ String(it.deadline).slice(0, 16).replace('T', ' ') }}</b>
            </div>
            <div v-if="!worklistUrgent.length" class="note">今日紧急事项已清空 ✅</div>
          </div>
          <div class="note" style="margin-top:14px">← 从左侧「今日必办」选一个案子开始处理</div>
        </div>
      </div>
    </div>

    <!-- ③ 当前主体（契约 GET /me） -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>当前主体</div>
        <div class="ops"><span class="note" style="margin:0">契约 GET /me</span></div>
      </div>
      <div class="desc">
        <div class="r"><span class="k">账号 ID</span><span class="v">{{ me.accountId }}</span></div>
        <div class="r"><span class="k">姓名</span><span class="v">{{ me.name }}</span></div>
        <div class="r">
          <span class="k">角色</span>
          <span class="v">{{ me.role }}（工作台 {{ wb?.layout === 'cockpit' ? '今日驾驶舱' : '仪表盘' }}）</span>
        </div>
        <div class="r"><span class="k">组织</span><span class="v">{{ me.org?.name }}（<span :title="me.org?.type">{{ orgTypeLabel(me.org?.type) }}</span>）</span></div>
        <div class="r">
          <span class="k">数据范围</span>
          <span class="v">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量（dataScope=null）' }}</span>
        </div>
        <div class="r" style="border-bottom:none">
          <span class="k">权限点</span>
          <span class="v" style="display:flex;flex-wrap:wrap;gap:6px">
            <span v-for="p in me.permissions" :key="p" class="tag inf" :title="p">{{ permLabel(p) }}</span>
          </span>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="note" style="text-align:center;padding:48px 0">加载主体中…</div>
</template>
