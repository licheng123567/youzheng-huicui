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
function openAssign(id: string) { aForm.value = { id, collectorId: '' }; adlg.value = true }
async function submitAssign() {
  if (!aForm.value.collectorId) { ElMessage.warning('请填催收员 id'); return }
  await act(aForm.value.id, '/cases/{id}/assign', '指派', { collectorId: String(aForm.value.collectorId) })
  adlg.value = false
}
onMounted(load)
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
      <el-table-column label="竞争态" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="row.competitionState==='CLAIMED'?'info':row.competitionState==='VIEWING'?'warning':'success'">
            {{ row.competitionState==='CLAIMED'?'已抢':row.competitionState==='VIEWING'?`查看中 ${row.viewerCount??0} 人`:'待抢' }}
          </el-tag>
        </template>
      </el-table-column>
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
    <el-dialog v-model="adlg" title="指派催收员（POST /cases/{id}/assign）" width="400px">
      <el-form label-width="90px"><el-form-item label="催收员 id"><el-input v-model="aForm.collectorId" placeholder="如 5(jx_co1)" /></el-form-item></el-form>
      <template #footer><el-button @click="adlg=false">取消</el-button><el-button type="primary" @click="submitAssign">指派</el-button></template>
    </el-dialog>
    <el-alert type="info" :closable="false" style="margin-top:12px"
      title="按角色登录看不同动作：CO(jx_co1) 见抢单 / VL(jx_vl) 见承接拒接 / SA(admin) 见开放抢单。服务端 x-permission+状态机双重校验。" />
  </el-card>
</template>
