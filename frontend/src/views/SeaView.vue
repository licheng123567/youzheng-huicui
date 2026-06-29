<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M3 公海：GET /sea(SeaCase 含竞争态/来源徽标/正在查看N人)。动作按 /me 权限点门控(FE authz)。
const auth = useAuth()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('')
const pool = ref<'platform' | 'provider' | 'open'>('provider') // /sea 必填池筛选
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
  if (error) { ElMessage.error('加载公海失败'); return }
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
// VL 拒接：须填原因（ReasonInput.reason，BR-M3-03a），否则后端 422
async function rejectCase(id: string) {
  try {
    const { value: reason } = await ElMessageBox.prompt('拒接原因（BR-M3-03a 必填）', '拒接案件', { inputValidator: (v: string) => !!v || '原因必填' })
    await act(id, '/cases/{id}/reject', '拒接', { reason })
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
onMounted(() => { load(); loadEvents() })

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
      <div class="t"><span class="bar"></span>公海</div>
      <div class="ops"><span class="note" style="margin:0">GET /sea · 共 {{ total }} · 动作按 /me 权限门控</span></div>
    </div>

    <!-- 池筛选分段（/sea 必填 pool） -->
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px;flex-wrap:wrap">
      <span class="segctrl">
        <span :class="{ on: pool === 'platform' }" @click="pool = 'platform'; load()">平台公海</span>
        <span :class="{ on: pool === 'provider' }" @click="pool = 'provider'; load()">服务商公海</span>
        <span :class="{ on: pool === 'open' }" @click="pool = 'open'; load()">开放抢单池</span>
      </span>
    </div>

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
        <tr v-for="row in items" :key="row.id">
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
            <!-- CO：抢单（本商公海/开放池可抢） -->
            <button v-if="auth.has('case.claim') && ['PROVIDER_SEA','OPEN_POOL'].includes(row.pool)" class="btn txt"
              :disabled="acting===row.id+'抢单'" @click="act(row.id,'/cases/{id}/claim','抢单')">抢单</button>
            <!-- VL：承接/拒接（已派到本商、待接） -->
            <template v-if="auth.has('case.accept') && row.pool==='PROVIDER_SEA'">
              <button class="btn txt" :disabled="acting===row.id+'承接'" @click="act(row.id,'/cases/{id}/accept','承接')">承接</button>
              <button class="btn txt dgc" :disabled="acting===row.id+'拒接'" @click="rejectCase(row.id)">拒接</button>
            </template>
            <!-- SA：开放抢单（平台公海案件→开放池） -->
            <button v-if="auth.has('case.dispatch') && row.pool==='PLATFORM_SEA'" class="btn txt"
              :disabled="acting===row.id+'开放抢单'" @click="act(row.id,'/cases/{id}/open-for-claim','开放抢单')">开放抢单</button>
            <!-- SA/SE：单案再派（平台公海案件→改派目标服务商 US-M3-02） -->
            <button v-if="auth.has('case.dispatch') && row.pool==='PLATFORM_SEA'" class="btn txt"
              :disabled="acting===row.id+'再派'" @click="openRedispatch(row.id)">再派</button>
            <!-- VL：指派给催收员（本商承接的公海案件） -->
            <button v-if="auth.has('case.assign') && row.pool==='PROVIDER_SEA'" class="btn txt"
              @click="openAssign(row.id)">指派</button>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td :colspan="auth.has('case.assign') ? 9 : 8" style="text-align:center;color:var(--sec);padding:32px 0">当前公海暂无可抢案件</td>
        </tr>
      </tbody>
    </table>

    <div class="alert info" style="margin-top:12px">
      按角色登录看不同动作：CO(jx_co1) 见抢单 / VL(jx_vl) 见承接拒接 / SA(admin) 见开放抢单。服务端 x-permission+状态机双重校验。
    </div>

    <el-dialog v-model="adlg" title="指派催收员（POST /cases/{id}/assign · 按余量推荐 BR-M3-23）" width="480px">
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
    </el-dialog>

    <!-- SA/SE 单案再派（POST /cases/{id}/redispatch · US-M3-02 · 门控 case.dispatch） -->
    <el-dialog v-model="rdlg" title="单案再派（POST /cases/{id}/redispatch · 改派目标服务商）" width="440px">
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
    </el-dialog>

    <!-- 批量分配（POST /cases/assign-batch · BR-M3-25 · 门控 case.assign） -->
    <el-dialog v-model="bdlg" title="批量分配（POST /cases/assign-batch · 不整批回滚）" width="520px">
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
    </el-dialog>

    <!-- 释放记录（GET /providers/{id}/release-records · BR-M3-27 · own-org 可见） -->
    <el-drawer v-model="reldlg" title="本商释放记录（GET /providers/{id}/release-records）" size="520px">
      <el-table v-loading="releaseLoading" :data="releaseRecords" border size="small">
        <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="row.kind==='AUTO'?'warning':'info'">{{ row.kind==='AUTO'?'自动回流':'主动释放' }}</el-tag></template></el-table-column>
        <el-table-column prop="caseId" label="案件" width="150" />
        <el-table-column prop="collectorName" label="催收员" width="100" />
        <el-table-column label="时间"><template #default="{ row }">{{ row.at ? String(row.at).slice(0,16).replace('T',' ') : '—' }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!releaseLoading && !releaseRecords.length" description="暂无释放记录" :image-size="60" />
    </el-drawer>
  </div>
</template>
