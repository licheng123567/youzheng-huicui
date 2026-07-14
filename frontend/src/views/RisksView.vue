<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import { riskLevelLabel, riskVerdictLabel, disposeTaskStatusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'
import AiReviewPanel from '../components/AiReviewPanel.vue'
import { downloadAuthedFile } from '../utils/download'

// M5 质检：风险看板(GET /risks·全量检测) + 处置归属(VL/PL 处置自己员工风险) + 平台复核 + 处置跟踪(仅平台)。
const auth = useAuth()
const router = useRouter()
// 平台/物业口径统一走 useRoleFields（单一真源，避免多处 ['SA','SE']/['PL','PC'] 定义漂移）。
// 处置/上报按后端 row.ownScope 权威判定(BR-M5-07a 谁的员工谁处置):
//   ownScope=true(本组织员工违规)→「处置」;false(非本组织/平台)→「上报」或平台「复核处置」。
//   PL 对本物业协调员(PC)违规=处置;对催收员违规=上报。平台只复核处置、无上报。
const { isPlatform, isProperty } = useRoleFields()
// 归属方(VL/PL/PC 有 qc.dispose)：可见本组织整改任务 + 提交整改回执。
const isOwner = computed(() => auth.has('qc.dispose'))
const risks = ref<any[]>([])
// 分页：此前硬编码 page:1 且无分页控件——超出一页的数据静默消失。
const page = ref(1)
const size = ref(30)
const total = ref(0)
function onPage(p: number) { page.value = p; load() }
const tasks = ref<any[]>([])
const loading = ref(false)
// 平台处理决定分档
const DECISIONS = [
  { v: 'INTERVIEW', label: '约谈' }, { v: 'WARNING', label: '警告' }, { v: 'RECTIFY', label: '限期整改' },
  { v: 'RESTRICT', label: '限制' }, { v: 'DEACTIVATE', label: '停用账号' },
]
const decisionLabel = (d?: string) => DECISIONS.find((x) => x.v === d)?.label ?? (d || '—')
// 片段 → 就地打开 AI 复盘右侧抽屉（与案件三栏/通话记录统一体验，不整页跳走）
const reviewOpen = ref(false); const reviewRecId = ref(''); const reviewCaseId = ref(''); const reviewOwner = ref(''); const reviewRoom = ref('')
function openReviewPanel(row: any) {
  if (!row.recordingId) return
  reviewRecId.value = String(row.recordingId); reviewCaseId.value = String(row.caseId || '')
  reviewOwner.value = row.ownerName || row.caseName || ''; reviewRoom.value = row.room || ''
  reviewOpen.value = true
}
// 统一鉴权下载（与别处一致，404 优雅提示）
function downloadRec(row: any) {
  if (!row.recordingId) return
  const name = (reviewOwner.value || '录音') + '_' + row.recordingId + '.mp3'
  downloadAuthedFile('/v1/recordings/' + row.recordingId + '/audio', name, '该录音暂无音频文件。')
}
const levelType = (l: string) => ({ HIGH: 'danger', MID: 'warning', LOW: 'info' } as any)[l] ?? 'info'
// 纯展示：风险级别 → ds-admin .tag 配色（dan/war/inf），仅用于 markup 着色
const levelTag = (l: string) => ({ HIGH: 'dan', MID: 'war', LOW: 'inf' } as any)[l] ?? 'inf'

async function load() {
  loading.value = true
  const r = await api.GET('/risks', { params: { query: { page: page.value, size: size.value } } as any })
  risks.value = (r.data as any)?.items ?? []
  total.value = (r.data as any)?.meta?.total ?? 0
  if (isPlatform.value || isOwner.value) {
    const t = await api.GET('/dispose-tasks', { params: { query: { page: 1, size: 20 } } as any })
    tasks.value = (t.data as any)?.items ?? []
  }
  loading.value = false
}

// 处置（归属方 VL/PL）：弹窗选 action(mark/to_qc/notify) + 填 note(可选)
const ddlg = ref(false); const dform = ref<any>({})
function openDispose(row: any) { dform.value = { id: row.id, action: 'mark', note: '' }; ddlg.value = true }
async function submitDispose() {
  const { error } = await api.POST('/risks/{id}/dispose', { params: { path: { id: dform.value.id } }, body: { action: dform.value.action, note: dform.value.note } as any })
  if (error) { ElMessage.error('处置失败：' + ((error as any)?.message ?? '非本组织员工风险/无权限')); return }
  ElMessage.success('已处置'); ddlg.value = false; load()
}
// 上报平台
async function escalate(row: any) {
  const { error } = await api.POST('/risks/{id}/escalate', { params: { path: { id: row.id } }, body: { note: '上报平台复核' } })
  if (error) { ElMessage.error('上报失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已上报平台'); load()
}
// 复核（平台）：确认属实时可选处理决定(分档) + 沟通内容 → 通知归属方与当事人；停用真置账号停用
const rdlg = ref(false); const rform = ref<any>({})
function openReview(row: any) { rform.value = { id: row.id, verdict: 'CONFIRMED', note: '', decision: 'INTERVIEW', decisionNote: '', violatorOrgName: row.violatorOrgName, violatorRole: row.violatorRole }; rdlg.value = true }
// 违规员工角色中文名 + 反馈对象(催收员→服务商负责人/协调员→物业负责人)
const violatorRoleLabel = (r?: string) => r === 'CO' ? '催收员' : r === 'PC' ? '协调员' : (r || '')
const feedbackTarget = (r?: string) => r === 'CO' ? '服务商负责人' : r === 'PC' ? '物业负责人' : '组织负责人'
async function submitReview() {
  const f = rform.value
  const body: any = { verdict: f.verdict, note: f.note }
  if (f.verdict === 'CONFIRMED') { body.decision = f.decision; body.decisionNote = f.decisionNote }
  const { error } = await api.POST('/risks/{id}/review', { params: { path: { id: f.id } }, body })
  if (error) { ElMessage.error('复核失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(f.verdict === 'CONFIRMED' ? '已复核并下发处理决定' : '复核完成'); rdlg.value = false; load()
}
// 整改回执（归属方 VL/PL）→ 任务 DONE + 通知平台
const cdlg = ref(false); const cform = ref<any>({})
function openRectify(t: any) { cform.value = { id: t.id, receiptNote: '' }; cdlg.value = true }
async function submitRectify() {
  const { error } = await api.POST('/dispose-tasks/{id}/rectify', { params: { path: { id: cform.value.id } }, body: { receiptNote: cform.value.receiptNote } as any })
  if (error) { ElMessage.error('回执失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('整改回执已提交'); cdlg.value = false; load()
}
// 强制停用（平台兜底 BR-M5-07d）：个人停权本该由组织自己执行（VL/PL 在成员管理停用本组织员工），
// 平台只在归属方逾期 72h 未回执时强停。enforceable 由服务端算好下发——前端不复刻时限判断。
const edlg = ref(false); const eform = ref<any>({})
function openEnforce(t: any) { eform.value = { id: t.id, target: t.targetAccount, org: t.provider, reason: '' }; edlg.value = true }
async function submitEnforce() {
  if (!eform.value.reason?.trim()) { ElMessage.warning('强制停用原因必填'); return }
  const { error } = await api.POST('/dispose-tasks/{id}/enforce', { params: { path: { id: eform.value.id } }, body: { reason: eform.value.reason.trim() } as any })
  if (error) { ElMessage.error('强制停用失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已强制停用该员工账号（已通知归属方负责人与当事人）'); edlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>质检风险看板</div>
      <div class="ops"><span class="note" style="margin:0">GET /risks · 全量检测 · 处置归属 / 平台复核</span></div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>业主</th>
          <th>房号</th>
          <th>项目</th>
          <th>批次</th>
          <th>电话</th>
          <th>违规员工</th>
          <th>风险类型</th>
          <th style="width:70px">级别</th>
          <th style="width:160px">片段（点击定位播放）</th>
          <th style="width:200px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in risks" :key="row.id">
          <td>{{ row.ownerName || row.caseName || '—' }}</td>
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td>{{ row.batchCode || '—' }}</td>
          <td>{{ row.phone || '—' }}</td>
          <td>
            {{ row.collectorName || row.collector || '—' }}
            <span v-if="row.violatorRole" class="tag inf" style="font-size:11px;margin-left:4px">{{ violatorRoleLabel(row.violatorRole) }}</span>
            <div v-if="row.violatorOrgName" class="mini" style="color:var(--sec)">{{ row.violatorOrgName }}</div>
          </td>
          <td>{{ row.type || row.riskType || '—' }}</td>
          <td><span class="tag" :class="levelTag(row.level)" :title="row.level">{{ riskLevelLabel(row.level) }}</span></td>
          <td>
            <template v-if="row.recordingId">
              <a class="btn txt" @click="openReviewPanel(row)" :title="'查看 AI 复盘 ' + (row.segmentTs || '')">
                🎧 {{ row.segmentTs || 'AI 复盘' }}
              </a>
              <a class="btn txt" @click="downloadRec(row)" :title="'下载录音 ' + (row.segmentTs || '')">
                ⬇ 下载
              </a>
            </template>
            <template v-else>
              <span style="color:var(--sec);font-size:12px">{{ row.segmentTs || row.snippet || '—' }}</span>
            </template>
          </td>
          <td>
            <button v-if="auth.has('qc.dispose') && row.ownScope" class="btn txt" @click="openDispose(row)">处置</button>
            <button v-if="auth.has('qc.escalate') && !isPlatform && !row.ownScope" class="btn txt" @click="escalate(row)">上报</button>
            <button v-if="auth.has('qc.review')" class="btn txt" @click="openReview(row)">复核处置</button>
            <span v-if="row.reviewed" class="tag suc" style="margin-left:4px;font-size:11px" :title="row.reviewed">{{ riskVerdictLabel(row.reviewed) }}</span>
          </td>
        </tr>
        <tr v-if="!loading && !risks.length">
          <td colspan="10" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="alert info">处置归属(BR-M5-07a)：服务商 VL 处置本商催收员风险、物业 PL 处置本物业协调员风险；平台只复核(CONFIRMED/FALSE_POSITIVE/ESCALATED)。</div>

    <template v-if="isPlatform || isOwner">
      <div class="card-h" style="margin-top:22px">
        <div class="t"><span class="bar"></span>{{ isPlatform ? '处置任务跟踪' : '我的整改任务' }}</div>
        <div class="ops"><span class="note" style="margin:0">{{ isPlatform ? 'GET /dispose-tasks · 平台监管 + 处理决定/整改回执' : '本组织整改任务 · 提交回执闭环' }}</span></div>
      </div>
      <table>
        <thead>
          <tr>
            <th>归属方</th>
            <th>当事人</th>
            <th>处理决定</th>
            <th>整改回执</th>
            <th style="width:100px">状态</th>
            <th>时间</th>
            <th style="width:150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td>{{ t.provider || '—' }}</td>
            <td>{{ t.targetAccount || '—' }}</td>
            <td :title="t.decisionNote || ''">{{ decisionLabel(t.decision) }}<span v-if="t.decisionNote" class="note" style="margin:0;font-size:11px"> · {{ t.decisionNote }}</span></td>
            <td>{{ t.receiptNote || '—' }}</td>
            <td :title="t.status"><span class="tag" :class="t.status === 'DONE' ? 'suc' : (t.status === 'IN_PROGRESS' ? 'war' : 'inf')">{{ disposeTaskStatusLabel(t.status) }}</span></td>
            <td>{{ t.receiptedAt || t.tm || '—' }}</td>
            <!-- 归属方：执行并回执。个人停权由组织自己在【成员管理】停用该员工，回执在此提交。 -->
            <td v-if="!isPlatform">
              <button v-if="t.status !== 'DONE'" class="btn txt" @click="openRectify(t)">提交整改回执</button>
              <span v-else class="tag suc" style="font-size:11px">已回执</span>
            </td>
            <!-- 平台：只有归属方逾期未回执的「停用账号」任务才可强制停用（enforceable 由服务端算，前端不复刻时限） -->
            <td v-else>
              <button v-if="t.enforceable" class="btn txt dgc" @click="openEnforce(t)">强制停用</button>
              <span v-else-if="t.enforcedAt" class="tag dan" style="font-size:11px" :title="t.enforceReason || ''">已强制停用</span>
              <span v-else-if="t.status === 'DONE'" class="tag suc" style="font-size:11px">归属方已处理</span>
              <span v-else class="note" style="margin:0;font-size:11px">待归属方处理</span>
            </td>
          </tr>
          <tr v-if="!tasks.length">
            <td colspan="7" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
          </tr>
        </tbody>
      </table>

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />
    </template>

    <DsDrawer v-model="ddlg" title="风险处置">
      <el-form label-width="80px">
        <el-form-item label="处置方式">
          <el-select v-model="dform.action" style="width:100%">
            <el-option label="标记 mark" value="mark" />
            <el-option label="转质检 to_qc" value="to_qc" />
            <el-option label="通知 notify" value="notify" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="dform.note" type="textarea" :rows="2" placeholder="可选" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ddlg=false">取消</el-button><el-button type="primary" @click="submitDispose">提交处置</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="rdlg" title="平台复核处置">
      <el-form label-width="80px">
        <el-form-item label="判定">
          <el-select v-model="rform.verdict">
            <el-option label="确认属实 CONFIRMED" value="CONFIRMED" />
            <el-option label="误报 FALSE_POSITIVE" value="FALSE_POSITIVE" />
            <el-option label="升级 ESCALATED" value="ESCALATED" />
          </el-select>
        </el-form-item>
        <template v-if="rform.verdict === 'CONFIRMED'">
          <el-form-item label="处理决定">
            <el-select v-model="rform.decision" style="width:100%">
              <el-option v-for="d in DECISIONS" :key="d.v" :label="d.label" :value="d.v" />
            </el-select>
          </el-form-item>
          <el-form-item label="沟通内容"><el-input v-model="rform.decisionNote" type="textarea" :rows="2" placeholder="随通知发给归属方与当事人（如整改要求/期限）" /></el-form-item>
          <div class="alert" :class="rform.decision === 'DEACTIVATE' ? 'warn' : 'info'" style="margin:0 0 6px">
            {{ rform.decision === 'DEACTIVATE' ? '⚠ 停用将立即停用当事人账号（无法登录），' : '处理决定将' }}反馈给<b>{{ rform.violatorOrgName || '归属组织' }} · {{ feedbackTarget(rform.violatorRole) }}</b>与当事人执行整改，并建整改任务跟踪。
          </div>
        </template>
        <el-form-item label="说明"><el-input v-model="rform.note" type="textarea" :rows="2" placeholder="复核备注（可选）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="rdlg=false">取消</el-button><el-button type="primary" @click="submitReview">提交复核</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="cdlg" title="提交整改回执">
      <el-form label-width="80px">
        <el-form-item label="整改回执"><el-input v-model="cform.receiptNote" type="textarea" :rows="3" placeholder="说明本次整改措施/结果（如已约谈当事人、已完成培训）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitRectify">提交回执</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="edlg" title="强制停用当事人账号">
      <div class="alert warn" style="margin-top:0;margin-bottom:10px">
        <span>个人停权本应由<b>归属方自己执行</b>（在其成员管理中停用该员工）。归属方已逾期未回执，平台在此强制停用——动作会写审计并同时通知归属方负责人与当事人。</span>
      </div>
      <el-form label-width="90px">
        <el-form-item label="归属方">{{ eform.org || '—' }}</el-form-item>
        <el-form-item label="当事人">{{ eform.target || '—' }}</el-form-item>
        <el-form-item label="强制原因" required>
          <el-input v-model="eform.reason" type="textarea" :rows="3" placeholder="如：高危违规且归属方逾期未处理" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="edlg=false">取消</el-button><el-button type="danger" @click="submitEnforce">确认强制停用</el-button></template>
    </DsDrawer>

    <!-- AI 复盘右侧抽屉（与案件三栏/通话记录统一体验）：质检点片段就地看录音回放+对话转写+风险高亮 -->
    <AiReviewPanel v-if="reviewRecId" v-model:open="reviewOpen" :recording-id="reviewRecId" :case-id="reviewCaseId" :owner-name="reviewOwner" :room="reviewRoom" />
  </div>
</template>
