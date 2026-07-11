<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import DsDrawer from '../components/DsDrawer.vue'

// M3 公海：GET /sea(SeaCase 含竞争态/来源徽标/正在查看N人)。动作按 /me 权限点门控(FE authz)。
// 池分段按**侧别**收（BR-M3-29；v1.18.0 开放池停用后两侧均无开放池 Tab）：平台侧(SA/SE)=平台公海——
// 服务商公海明细是服务商内务，平台不看（后端对平台的 pool=provider 也已改为空集）；服务商侧(VL/CO)=本商公海——
// 平台公海对他们从来是空集，留着分段只是暴露一个永远为空的概念。物业(PL/PC)无 sea.view，进不了本页。
const auth = useAuth()
const isPlatformSide = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')
// 待接单 tab 只对能承接的人(服务商负责人)有意义——按权限点判,不写死角色名
const canAccept = computed(() => auth.has('case.accept'))
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('')
const pool = ref<'platform' | 'provider' | 'open'>('provider') // /sea 必填池筛选；平台侧 onMounted 落到 platform
// 服务商公海池里 S1(待接单 status=PENDING_DISPATCH)和 S2(已接单 status=PROVIDER_SEA)**pool 相同**，
// 后端一次返回、前端按 status 分两个 tab（BR-M3-03a：接单→进服务商公海待分配；拒接→退回平台公海）。
const subTab = ref<'accept' | 'list'>('list')
const isPending = (r: any) => r.status === 'PENDING_DISPATCH'
const pendingRows = computed(() => (pool.value === 'provider' ? items.value.filter(isPending) : []))
const seaRows = computed(() => (pool.value === 'provider' ? items.value.filter((r) => !isPending(r)) : items.value))
// 待接单按**批次**分组（原型口径：平台整批/拆单派来，接单拒接都是批次粒度；后端端点是案件级，整批=逐案调用）
const pendingBatches = computed(() => {
  const m = new Map<string, any>()
  for (const r of pendingRows.value) {
    const g = m.get(r.batchId) ?? { batchId: r.batchId, projectName: r.projectName, cases: [] as any[], dueCents: 0 }
    g.cases.push(r); g.dueCents += r.dueCents ?? 0
    m.set(r.batchId, g)
  }
  return [...m.values()]
})
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const poolName = (p: string) => ({ PLATFORM_SEA: '平台公海', PROVIDER_SEA: '服务商公海', OPEN_POOL: '开放抢单池', PRIVATE: '私海' } as any)[p] ?? p
// T2 倒计时（基于 t2DeadlineAt）：剩余天/时；<24h 标红(BR-M3-13a 预警提前量)
function t2Hours(at: string) { return (new Date(at).getTime() - Date.now()) / 3_600_000 }
function t2Countdown(at: string) { const h = t2Hours(at); if (h <= 0) return '已到期'; return h >= 24 ? `${Math.floor(h / 24)}天` : `${Math.ceil(h)}小时` }
function t2Urgent(at: string) { const h = t2Hours(at); return h > 0 && h < 24 }

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/sea', { params: { query: { pool: pool.value, page: 1, size: 50 } } })
  loading.value = false
  if (error) {
    // sea.view 是 2026-07 新加的权限点(BR-M3-29),权限集签在 JWT 里——
    // 收权部署前登录的老 token 没有它,会在这里吃 403。指条明路,别只报一句失败。
    const code = (error as any)?.code
    ElMessage.error(code === 'PERM_403'
      ? '加载公海失败：登录凭证里还没有新权限（近期有权限调整），请退出后重新登录'
      : '加载公海失败：' + ((error as any)?.message ?? ''))
    return
  }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}

// 通用 action：POST /cases/{id}/{verb}，成功刷新，失败按 Error 信封提示(409 已被抢 / 403 无权限)。
async function act(id: string, path: any, verb: string, body?: any) {
  acting.value = id + verb
  const { error } = await api.POST(path, { params: { path: { id } }, ...(body ? { body } : {}) } as any)
  acting.value = ''
  if (error) { ElMessage.error(`${verb}失败：${(error as any)?.message ?? '冲突或无权限'}`); return }
  ElMessage.success(`${verb}成功`)
  load()
}
// 整批承接/拒接（BR-M3-03a）：后端只有案件级端点，整批=逐案串行调用；
// 一案失败即停并提示进度，避免半批接单后前端谎报「整批成功」。
async function acceptBatch(b: any) {
  acting.value = 'accept' + b.batchId
  let done = 0
  for (const c of b.cases) {
    const { error } = await api.POST('/cases/{id}/accept', { params: { path: { id: c.id } } } as any)
    if (error) { ElMessage.error(`承接中断：第 ${done + 1}/${b.cases.length} 件失败（${(error as any)?.message ?? ''}）`); acting.value = ''; load(); return }
    done++
  }
  acting.value = ''
  ElMessage.success(`已承接 ${done} 件，进入服务商公海待分配`)
  load()
}
async function rejectBatch(b: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt('拒接原因（BR-M3-03a 必填·整批退回平台公海重派）', '拒接批次', { inputValidator: (v: string) => !!v || '原因必填' })
    acting.value = 'reject' + b.batchId
    let done = 0
    for (const c of b.cases) {
      const { error } = await api.POST('/cases/{id}/reject', { params: { path: { id: c.id } }, body: { reason } } as any)
      if (error) { ElMessage.error(`拒接中断：第 ${done + 1}/${b.cases.length} 件失败`); acting.value = ''; load(); return }
      done++
    }
    acting.value = ''
    ElMessage.success(`已拒接 ${done} 件，退回平台公海重派`)
    load()
  } catch { /* 取消 */ }
}
// VL 指派：把本商承接的案件分给某催收员（POST /cases/{id}/assign）
const adlg = ref(false); const aForm = ref<any>({ id: '', collectorId: '' })
const caps = ref<any[]>([]); const capHoldCap = ref(0)   // 催收员余量+推荐(BR-M3-23)
async function openAssign(id: string) {
  aForm.value = { id, collectorId: '' }; caps.value = []; adlg.value = true
  const orgId = auth.me?.org?.id
  if (orgId) {
    const { data } = await api.GET('/providers/{id}/collector-capacity', { params: { path: { id: String(orgId) } } } as any)
    caps.value = (data as any)?.items ?? []; capHoldCap.value = (data as any)?.holdCap ?? 0
    const rec = caps.value.find((c) => c.recommended)
    if (rec) aForm.value.collectorId = rec.collectorId   // 默认选推荐(余量最大)
  }
}
async function submitAssign() {
  if (!aForm.value.collectorId) { ElMessage.warning('请填催收员 id'); return }
  await act(aForm.value.id, '/cases/{id}/assign', '指派', { collectorId: String(aForm.value.collectorId) })
  adlg.value = false
}
// SA/SE 单案再派(POST /cases/{id}/redispatch · US-M3-02)：平台公海案件改派目标服务商 org。
// 门控 case.dispatch；409 BIZ_REDISPATCH_GUARD=不可再派回原退回服务商/已停用。
const rdlg = ref(false)
const redispatchForm = ref<{ id: string; providerId: string | undefined }>({ id: '', providerId: undefined })
const providers = ref<any[]>([]) // 目标可选服务商 org（type=PROVIDER）
async function loadProviders() {
  const { data } = await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })
  providers.value = ((data as any)?.items ?? []).filter((o: any) => o.type === 'PROVIDER')
}
function openRedispatch(id: string) {
  redispatchForm.value = { id, providerId: undefined }
  rdlg.value = true
  if (!providers.value.length) loadProviders()
}
async function submitRedispatch() {
  if (!redispatchForm.value.providerId) { ElMessage.warning('请选择目标服务商'); return }
  acting.value = redispatchForm.value.id + '再派'
  const { error } = await api.POST('/cases/{id}/redispatch', {
    params: { path: { id: redispatchForm.value.id } },
    body: { providerId: String(redispatchForm.value.providerId) },
  } as any)
  acting.value = ''
  if (error) {
    const e = error as any
    ElMessage.error(e?.code === 'BIZ_REDISPATCH_GUARD' ? '不可再派回原退回服务商（或目标已停用）' : `再派失败：${e?.message ?? '冲突或无权限'}`)
    return
  }
  ElMessage.success('再派成功'); rdlg.value = false; load()
}

// SA/SE/VL 批量分配(POST /cases/assign-batch · BR-M3-25)：多选案件→指派给某催收员，evenSplit 可均摊。
// 门控 case.assign；返回 {assigned[],rejected[]}（rejected 含超额/不可分原因）。
const selectedCaseIds = ref<string[]>([])
const bdlg = ref(false)
const batchForm = ref<{ collectorId: string | undefined; evenSplit: boolean }>({ collectorId: undefined, evenSplit: false })
const batchResult = ref<{ assigned: string[]; rejected: { caseId?: string; reason?: string }[] } | null>(null)
function onSelectionChange(rows: any[]) { selectedCaseIds.value = rows.map((r) => r.id) }
function openBatchAssign() {
  if (!selectedCaseIds.value.length) { ElMessage.warning('请先勾选案件'); return }
  batchForm.value = { collectorId: undefined, evenSplit: false }
  batchResult.value = null
  bdlg.value = true
}
async function submitBatchAssign() {
  if (!batchForm.value.collectorId) { ElMessage.warning('请填催收员 id'); return }
  acting.value = 'batch'
  const { data, error } = await api.POST('/cases/assign-batch', {
    body: { caseIds: selectedCaseIds.value, collectorId: String(batchForm.value.collectorId), evenSplit: batchForm.value.evenSplit },
  } as any)
  acting.value = ''
  if (error) { ElMessage.error(`批量分配失败：${(error as any)?.message ?? '无权限'}`); return }
  batchResult.value = { assigned: (data as any)?.assigned ?? [], rejected: (data as any)?.rejected ?? [] }
  ElMessage.success(`已分配 ${batchResult.value.assigned.length} 件，被拒 ${batchResult.value.rejected.length} 件`)
  load()
}

// VL 释放记录(GET /providers/{id}/release-records · BR-M3-27)：本商释放历史（own-org 可见）。
const reldlg = ref(false)
const releaseRecords = ref<any[]>([])
const releaseLoading = ref(false)
async function openReleaseRecords() {
  reldlg.value = true
  releaseRecords.value = []
  const orgId = auth.me?.org?.id
  if (!orgId) return
  releaseLoading.value = true
  const { data } = await api.GET('/providers/{id}/release-records', { params: { path: { id: String(orgId) }, query: { page: 1, size: 50 } } } as any)
  releaseLoading.value = false
  releaseRecords.value = (data as any)?.items ?? []
}

// 公海事件日志(GET /sea/events · BR-M3-22 · 轮询)
const events = ref<any[]>([])
const EV_LABEL: Record<string, string> = { ENTER: '入池', CLAIM: '抢单', RELEASE: '释放', RETURN: '退回', REDISPATCH: '再派', OPEN: '开放', ASSIGN: '指派' }
async function loadEvents() {
  const { data } = await api.GET('/sea/events', { params: { query: { page: 1, size: 15 } } as any })
  events.value = (data as any)?.items ?? []
}
onMounted(() => {
  // 路由守卫已按角色放行到这里，auth.me 必已就位。平台侧默认落平台公海——
  // 它进来要干的活(单案再派)都在平台公海行上,而 provider 池对平台已是空集。
  if (isPlatformSide.value) pool.value = 'platform'
  load(); loadEvents()
})

// ===== 纯展示辅助（不改数据流）=====
// 竞争态 → ds-admin .tag 配色 + 文案
function compTag(row: any) { return row.competitionState === 'CLAIMED' ? 'inf' : row.competitionState === 'VIEWING' ? 'war' : 'suc' }
function compText(row: any) { return row.competitionState === 'CLAIMED' ? '已抢' : row.competitionState === 'VIEWING' ? `查看中 ${row.viewerCount ?? 0} 人` : '待抢' }
// 事件类型 → .tag 配色
const EV_TAG: Record<string, string> = { ENTER: 'inf', CLAIM: 'suc', RELEASE: 'war', RETURN: 'dan', REDISPATCH: 'pri', OPEN: 'pri', ASSIGN: 'inf' }
const evTag = (ev: string) => EV_TAG[ev] ?? 'inf'
</script>

<template>
  <!-- 实时事件流水（GET /sea/events · BR-M3-22 · 轮询非SSE）：顶部整宽横向条 -->
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span><span class="dot-blink"></span>实时事件流水</div>
      <div class="ops">
        <span class="note" style="margin:0">公海竞争态 · 近期流转</span>
        <button class="btn txt sm" @click="loadEvents">刷新</button>
      </div>
    </div>
    <div v-if="events.length" style="display:flex;gap:10px;overflow-x:auto;padding-bottom:4px">
      <div v-for="e in events" :key="e.id"
        style="flex:0 0 auto;display:flex;align-items:center;gap:6px;border:1px solid var(--bd);border-radius:16px;padding:5px 12px;background:#fafcff;white-space:nowrap">
        <span class="tag" :class="evTag(e.event)" style="font-size:11px">{{ EV_LABEL[e.event] || e.event }}</span>
        <span style="font-size:12px;color:var(--reg)">案件 #{{ e.caseId }}（{{ e.ownerName }}）</span>
        <span style="color:var(--ph);font-size:11px;font-variant-numeric:tabular-nums">{{ String(e.at).slice(0,16).replace('T',' ') }}</span>
      </div>
    </div>
    <div v-else class="note" style="text-align:center;padding:18px 0">暂无事件</div>
  </div>

  <!-- 公海列表 -->
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>案件公海</div>
      <div class="ops"><span class="note" style="margin:0">GET /sea · 共 {{ total }} · 动作按 /me 权限门控</span></div>
    </div>

    <!-- 池筛选分段（/sea 必填 pool · 平台侧=平台公海 / 服务商侧=待接单(仅VL)+本商公海；v1.18.0 开放池停用 BR-M3-29） -->
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px;flex-wrap:wrap">
      <span class="segctrl">
        <span v-if="isPlatformSide" :class="{ on: pool === 'platform' }" @click="pool = 'platform'; load()">平台公海</span>
        <span v-if="!isPlatformSide && canAccept" :class="{ on: pool === 'provider' && subTab === 'accept' }"
          @click="pool = 'provider'; subTab = 'accept'; load()">待接单<span v-if="pendingRows.length" class="tag dan" style="margin-left:4px">{{ pendingRows.length }}</span></span>
        <span v-if="!isPlatformSide" :class="{ on: pool === 'provider' && subTab === 'list' }"
          @click="pool = 'provider'; subTab = 'list'; load()">服务商公海</span>
      </span>
    </div>

    <!-- 待接单（仅 VL·批次粒度 BR-M3-03a）：接单→进服务商公海待分配；拒接→整批退回平台公海重派 -->
    <template v-if="pool === 'provider' && subTab === 'accept' && canAccept">
      <div class="alert info" style="margin-bottom:12px">平台派单（整批/拆单）到本服务商后需<b>承接</b>：接单 → 案件进入<b>服务商公海</b>待分配给催收员；拒接 → 退回平台公海由平台重派（须填原因）。</div>
      <table v-loading="loading">
        <thead>
          <tr><th>批次</th><th>项目</th><th style="width:90px">案件数</th><th style="width:130px">应收合计</th><th style="width:200px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="b in pendingBatches" :key="b.batchId">
            <td><b>批次 #{{ b.batchId }}</b></td>
            <td>{{ b.projectName || '—' }}</td>
            <td class="num">{{ b.cases.length }}</td>
            <td class="num">{{ yuan(b.dueCents) }}</td>
            <td @click.stop>
              <button class="btn txt" :disabled="acting==='accept'+b.batchId" @click="acceptBatch(b)">接单（承接）</button>
              <button class="btn txt dgc" :disabled="acting==='reject'+b.batchId" @click="rejectBatch(b)">拒接</button>
            </td>
          </tr>
          <tr v-if="!loading && !pendingBatches.length">
            <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无待接单批次</td>
          </tr>
        </tbody>
      </table>
    </template>

    <template v-else>

    <!-- 工具栏：批量分配(case.assign) / 释放记录(own-org · VL) -->
    <div v-if="auth.has('case.assign')" class="toolbar" style="margin-bottom:12px">
      <button class="btn sm" :disabled="!selectedCaseIds.length" @click="openBatchAssign">批量分配（已选 {{ selectedCaseIds.length }}）</button>
      <button class="btn df sm" @click="openReleaseRecords">释放记录</button>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th v-if="auth.has('case.assign')" style="width:34px"></th>
          <th style="width:90px">业主</th>
          <th style="width:80px">房号</th>
          <th>项目</th>
          <th style="width:110px">应收</th>
          <th style="width:120px">来源池</th>
          <th style="width:130px">竞争态</th>
          <th style="width:120px">距退回(T2)</th>
          <th style="width:230px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in seaRows" :key="row.id">
          <td v-if="auth.has('case.assign')" @click.stop>
            <input type="checkbox" :checked="selectedCaseIds.includes(row.id)"
              @change="onSelectionChange(items.filter((r:any) => selectedCaseIds.includes(r.id) !== (r.id === row.id)))" />
          </td>
          <td>{{ row.ownerName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td><span class="tag inf">{{ poolName(row.sourceBadge ?? row.pool) }}</span></td>
          <td><span class="tag" :class="compTag(row)">{{ compText(row) }}</span></td>
          <td>
            <span v-if="row.t2DeadlineAt" class="tag" :class="t2Urgent(row.t2DeadlineAt) ? 'dan' : 'inf'">{{ t2Countdown(row.t2DeadlineAt) }}</span>
            <span v-else>—</span>
          </td>
          <td @click.stop>
            <!-- CO：抢单（仅本商公海已承接 S2·组织内工作分配；v1.18.0 开放池停用，跨商抢单取消） -->
            <button v-if="auth.has('case.claim') && row.pool==='PROVIDER_SEA' && row.status!=='PENDING_DISPATCH'" class="btn txt"
              :disabled="acting===row.id+'抢单'" @click="act(row.id,'/cases/{id}/claim','抢单')">抢单</button>
            <!-- SA/SE：单案再派（平台公海案件→改派目标服务商 US-M3-02） -->
            <button v-if="auth.has('case.dispatch') && row.pool==='PLATFORM_SEA'" class="btn txt"
              :disabled="acting===row.id+'再派'" @click="openRedispatch(row.id)">再派</button>
            <!-- VL：指派给催收员（本商已承接(S2)的公海案件；S1 先走待接单 tab） -->
            <button v-if="auth.has('case.assign') && row.pool==='PROVIDER_SEA' && row.status!=='PENDING_DISPATCH'" class="btn txt"
              @click="openAssign(row.id)">指派</button>
          </td>
        </tr>
        <tr v-if="!loading && !seaRows.length">
          <td :colspan="auth.has('case.assign') ? 9 : 8" style="text-align:center;color:var(--sec);padding:32px 0">当前公海暂无可抢案件</td>
        </tr>
      </tbody>
    </table>
    </template>

    <div class="alert info" style="margin-top:12px">
      按角色登录看不同动作：CO(jx_co1) 见抢单(本商公海) / VL(jx_vl) 见承接拒接 / SA(admin) 见再派。服务端 x-permission+状态机双重校验。
    </div>

    <DsDrawer v-model="adlg" title="指派催收员" :width="480">
      <div style="color:#909399;font-size:12px;margin-bottom:6px">持有上限 holdCap={{ capHoldCap }}；点行选定（默认选余量最大的推荐者）</div>
      <el-table :data="caps" border size="small" highlight-current-row @row-click="(r:any)=>aForm.collectorId=r.collectorId" style="cursor:pointer">
        <el-table-column width="40"><template #default="{row}"><el-radio :model-value="aForm.collectorId" :label="row.collectorId"><span></span></el-radio></template></el-table-column>
        <el-table-column prop="name" label="催收员" />
        <el-table-column label="持仓"><template #default="{row}">{{ row.holding }}</template></el-table-column>
        <el-table-column label="余量"><template #default="{row}"><el-progress :percentage="capHoldCap?Math.round(row.remaining/capHoldCap*100):0" :stroke-width="10" /></template></el-table-column>
        <el-table-column label="推荐" width="70"><template #default="{row}"><el-tag v-if="row.recommended" size="small" type="success">推荐</el-tag></template></el-table-column>
      </el-table>
      <el-form label-width="90px" style="margin-top:10px"><el-form-item label="催收员 id"><el-input v-model="aForm.collectorId" placeholder="点上表行或手填" /></el-form-item></el-form>
      <template #footer><el-button @click="adlg=false">取消</el-button><el-button type="primary" @click="submitAssign">指派</el-button></template>
    </DsDrawer>

    <!-- SA/SE 单案再派（POST /cases/{id}/redispatch · US-M3-02 · 门控 case.dispatch） -->
    <DsDrawer v-model="rdlg" title="单案再派" :width="440">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="将平台公海案件改派至目标服务商；不可再派回原退回服务商（409 BIZ_REDISPATCH_GUARD）。" />
      <el-form label-width="90px">
        <el-form-item label="目标服务商">
          <el-select v-model="redispatchForm.providerId" placeholder="选择服务商 org" style="width:100%">
            <el-option v-for="p in providers" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="rdlg=false">取消</el-button><el-button type="primary" :loading="acting===redispatchForm.id+'再派'" @click="submitRedispatch">再派</el-button></template>
    </DsDrawer>

    <!-- 批量分配（POST /cases/assign-batch · BR-M3-25 · 门控 case.assign） -->
    <DsDrawer v-model="bdlg" title="批量分配" :width="520">
      <div style="color:#909399;font-size:12px;margin-bottom:8px">已选 {{ selectedCaseIds.length }} 件，指派给同一催收员；超持有上限的将被拒（BR-M3-06）。</div>
      <el-form label-width="100px">
        <el-form-item label="催收员 id"><el-input v-model="batchForm.collectorId" placeholder="催收员 id" /></el-form-item>
        <el-form-item label="按余量均摊"><el-switch v-model="batchForm.evenSplit" /><span style="color:#909399;font-size:12px;margin-left:8px">evenSplit（BR-M3-25）</span></el-form-item>
      </el-form>
      <template v-if="batchResult">
        <el-divider content-position="left">分配结果</el-divider>
        <el-tag type="success" size="small">成功 {{ batchResult.assigned.length }}</el-tag>
        <el-tag type="danger" size="small" style="margin-left:8px">被拒 {{ batchResult.rejected.length }}</el-tag>
        <el-table v-if="batchResult.rejected.length" :data="batchResult.rejected" border size="small" style="margin-top:8px">
          <el-table-column prop="caseId" label="案件" width="160" />
          <el-table-column prop="reason" label="拒绝原因" />
        </el-table>
      </template>
      <template #footer><el-button @click="bdlg=false">关闭</el-button><el-button type="primary" :loading="acting==='batch'" @click="submitBatchAssign">分配</el-button></template>
    </DsDrawer>

    <!-- 释放记录（GET /providers/{id}/release-records · BR-M3-27 · own-org 可见） -->
    <DsDrawer v-model="reldlg" title="本商释放记录" :width="520">
      <el-table v-loading="releaseLoading" :data="releaseRecords" border size="small">
        <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="row.kind==='AUTO'?'warning':'info'">{{ row.kind==='AUTO'?'自动回流':'主动释放' }}</el-tag></template></el-table-column>
        <el-table-column prop="caseId" label="案件" width="150" />
        <el-table-column prop="collectorName" label="催收员" width="100" />
        <el-table-column label="时间"><template #default="{ row }">{{ row.at ? String(row.at).slice(0,16).replace('T',' ') : '—' }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!releaseLoading && !releaseRecords.length" description="暂无释放记录" :image-size="60" />
    </DsDrawer>
  </div>
</template>
