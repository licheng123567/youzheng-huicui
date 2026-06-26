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
// 公海事件日志(GET /sea/events · BR-M3-22 · 轮询)
const events = ref<any[]>([])
const EV_LABEL: Record<string, string> = { ENTER: '入池', CLAIM: '抢单', RELEASE: '释放', RETURN: '退回', REDISPATCH: '再派', OPEN: '开放', ASSIGN: '指派' }
async function loadEvents() {
  const { data } = await api.GET('/sea/events', { params: { query: { page: 1, size: 15 } } as any })
  events.value = (data as any)?.items ?? []
}
onMounted(() => { load(); loadEvents() })
</script>

<template>
  <el-card :header="`公海（GET /sea · 共 ${total} · 动作按 /me 权限门控）`">
    <el-radio-group v-model="pool" style="margin-bottom:12px" @change="load">
      <el-radio-button label="platform">平台公海</el-radio-button>
      <el-radio-button label="provider">服务商公海</el-radio-button>
      <el-radio-button label="open">开放抢单池</el-radio-button>
    </el-radio-group>
    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="ownerName" label="业主" width="90" />
      <el-table-column prop="room" label="房号" width="80" />
      <el-table-column prop="projectName" label="项目" />
      <el-table-column label="应收" width="100"><template #default="{ row }">{{ yuan(row.dueCents) }}</template></el-table-column>
      <el-table-column label="来源池" width="120"><template #default="{ row }"><el-tag size="small">{{ poolName(row.sourceBadge ?? row.pool) }}</el-tag></template></el-table-column>
      <el-table-column label="竞争态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.competitionState==='CLAIMED'?'info':row.competitionState==='VIEWING'?'warning':'success'">
            {{ row.competitionState==='CLAIMED'?'已抢':row.competitionState==='VIEWING'?`查看中 ${row.viewerCount??0} 人`:'待抢' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="距退回(T2)" width="120"><template #default="{ row }">
        <el-tag v-if="row.t2DeadlineAt" size="small" :type="t2Urgent(row.t2DeadlineAt)?'danger':'info'">{{ t2Countdown(row.t2DeadlineAt) }}</el-tag>
        <span v-else>—</span>
      </template></el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <!-- CO：抢单（本商公海/开放池可抢） -->
          <el-button v-if="auth.has('case.claim') && ['PROVIDER_SEA','OPEN_POOL'].includes(row.pool)" size="small" type="primary"
            :loading="acting===row.id+'抢单'" @click="act(row.id,'/cases/{id}/claim','抢单')">抢单</el-button>
          <!-- VL：承接/拒接（已派到本商、待接） -->
          <template v-if="auth.has('case.accept') && row.pool==='PROVIDER_SEA'">
            <el-button size="small" :loading="acting===row.id+'承接'" @click="act(row.id,'/cases/{id}/accept','承接')">承接</el-button>
            <el-button size="small" :loading="acting===row.id+'拒接'" @click="rejectCase(row.id)">拒接</el-button>
          </template>
          <!-- SA：开放抢单（平台公海案件→开放池） -->
          <el-button v-if="auth.has('case.dispatch') && row.pool==='PLATFORM_SEA'" size="small"
            :loading="acting===row.id+'开放抢单'" @click="act(row.id,'/cases/{id}/open-for-claim','开放抢单')">开放抢单</el-button>
          <!-- VL：指派给催收员（本商承接的公海案件） -->
          <el-button v-if="auth.has('case.assign') && row.pool==='PROVIDER_SEA'" size="small"
            @click="openAssign(row.id)">指派</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 实时事件日志（GET /sea/events · BR-M3-22 · 轮询非SSE） -->
    <el-divider content-position="left">实时事件日志（近期流转 · <el-button text size="small" @click="loadEvents">刷新</el-button>）</el-divider>
    <el-timeline v-if="events.length">
      <el-timeline-item v-for="e in events" :key="e.id" :timestamp="String(e.at).slice(0,16).replace('T',' ')" placement="top">
        <el-tag size="small">{{ EV_LABEL[e.event] || e.event }}</el-tag> 案件 #{{ e.caseId }}（{{ e.ownerName }}）
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="暂无事件" :image-size="40" />

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
    <el-alert type="info" :closable="false" style="margin-top:12px"
      title="按角色登录看不同动作：CO(jx_co1) 见抢单 / VL(jx_vl) 见承接拒接 / SA(admin) 见开放抢单。服务端 x-permission+状态机双重校验。" />
  </el-card>
</template>
