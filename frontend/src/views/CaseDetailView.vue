<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M4 催收完整作业台：概览/联系人 · 通话AI · 跟进承诺工单 · 回款减免 · 法务存证。动作按 /me 权限门控。
const route = useRoute(); const router = useRouter(); const auth = useAuth()
const id = String(route.params.id)
const d = ref<any>(null)
const promises = ref<any[]>([]); const tickets = ref<any[]>([]); const legalDocs = ref<any[]>([]); const repays = ref<any[]>([])
const latest = ref<any>(null); const review = ref<any>(null)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

async function loadAll() {
  const det = await api.GET('/cases/{id}', { params: { path: { id } } })
  if (det.error) { ElMessage.error('加载失败'); return }
  d.value = det.data
  promises.value = ((await api.GET('/cases/{id}/promises', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  tickets.value = ((await api.GET('/cases/{id}/tickets', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  legalDocs.value = ((await api.GET('/cases/{id}/legal-docs', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)).data as any)?.items ?? []
  // 回款明细经批次端点过滤本案（无独立 per-case 列表端点）
  const bid = d.value?.case?.batchId
  if (bid) {
    const rl = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: String(bid) }, query: { page: 1, size: 100 } } } as any)
    repays.value = ((rl.data as any)?.items ?? []).filter((x: any) => String(x.caseId) === String(d.value?.case?.id))
  }
}
async function getLatest() {
  const { data, error } = await api.GET('/cases/{id}/recordings/latest', { params: { path: { id } } })
  if (error) { ElMessage.error('获取失败'); return }
  latest.value = data
  if (!(data as any)?.hasRecording) ElMessage.info('暂无录音（App 通话结束自动上传 / 无则手动上传）')
}
async function loadReview(recId: string) {
  const { data, error } = await api.GET('/recordings/{id}/ai-review', { params: { path: { id: recId } } })
  if (error) { ElMessage.error('复盘加载失败'); return }
  review.value = data
}
// 录音：上传 / 解析 / 结果标记
async function uploadRecording(e: any) {
  const file = e.target.files?.[0]; if (!file) return
  const fd = new FormData(); fd.append('file', file)
  const r = await fetch(`/v1/cases/${id}/recordings`, { method: 'POST', headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }, body: fd })
  if (!r.ok) { ElMessage.error('上传失败 ' + r.status); return }
  ElMessage.success('已上传，解析中'); getLatest()
}
async function parseRec(recId: string) {
  const { error } = await api.POST('/recordings/{id}/parse', { params: { path: { id: recId } } } as any)
  if (error) { ElMessage.error('解析失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已触发解析'); getLatest()
}
const mkdlg = ref(false); const mkForm = ref<any>({})
function openMark(recId: string) { mkForm.value = { recId, mark: 'PROMISED' }; mkdlg.value = true }
async function submitMark() {
  const { error } = await api.POST('/recordings/{id}/ai-review', { params: { path: { id: mkForm.value.recId } }, body: { mark: mkForm.value.mark } as any })
  if (error) { ElMessage.error('标记失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已标记通话结果'); mkdlg.value = false
}

// 通用动作对话框（跟进/承诺/工单/缴费/结案/还款/法务/存证）
const dlg = ref<{ open: boolean; kind: string; title: string }>({ open: false, kind: '', title: '' })
const form = ref<any>({})
function openAct(kind: string, title: string, sourceSuggestionId?: string) {
  form.value = kind === 'follow' ? { content: '', method: 'CALL', sourceSuggestionId }
    : kind === 'promise' ? { date: '', amountYuan: 0, installments: [], sourceSuggestionId }
    : kind === 'ticket' ? { type: '上门核实', note: '', sourceSuggestionId }
    : kind === 'close' ? { closeKind: 'WITHDRAWN', reason: '' }
    : kind === 'repay' ? { amountYuan: 0, channel: 'WECHAT_QR', paidAt: '' }
    : kind === 'legal' ? { type: 'COLLECTION_LETTER' }
    : kind === 'evidence' ? { scene: 'RECORDING', note: '' }
    : { channel: 'SMS', sourceSuggestionId }
  dlg.value = { open: true, kind, title }
}
function addInstallment() { (form.value.installments ||= []).push({ seq: form.value.installments.length + 1, dueDate: '', amountYuan: 0 }) }
async function submitAct() {
  const k = dlg.value.kind, f = form.value
  let res: any
  if (k === 'follow') res = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: f.content, method: f.method, sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'promise') {
    const inst = (f.installments || []).map((x: any) => ({ seq: x.seq, dueDate: x.dueDate, amountCents: Math.round(x.amountYuan * 100) }))
    res = await api.POST('/cases/{id}/promises', { params: { path: { id } }, body: { date: f.date, amountCents: Math.round(f.amountYuan * 100), installments: inst.length ? inst : undefined, sourceSuggestionId: f.sourceSuggestionId } as any })
  }
  else if (k === 'ticket') res = await api.POST('/cases/{id}/tickets', { params: { path: { id } }, body: { type: f.type, note: f.note, sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'close') res = await api.POST('/cases/{id}/close', { params: { path: { id } }, body: { kind: f.closeKind, reason: f.reason } as any })
  else if (k === 'repay') res = await api.POST('/cases/{id}/repay-lines', { params: { path: { id } }, body: { amountCents: Math.round(f.amountYuan * 100), channel: f.channel, paidAt: f.paidAt } as any })
  else if (k === 'legal') res = await api.POST('/cases/{id}/legal-docs', { params: { path: { id } }, body: { type: f.type } as any })
  else if (k === 'evidence') res = await api.POST('/cases/{id}/evidence', { params: { path: { id } }, body: { scene: f.scene, note: f.note } as any })
  else res = await api.POST('/cases/{id}/pay-links', { params: { path: { id } }, body: { channel: f.channel, sourceSuggestionId: f.sourceSuggestionId } as any })
  if (res.error) { ElMessage.error('提交失败：' + ((res.error as any)?.message ?? '')); return }
  ElMessage.success(dlg.value.title + '成功'); dlg.value.open = false; loadAll()
}
// 工单处理
const hdlg = ref(false); const hForm = ref<any>({})
function openHandle(t: any) { hForm.value = { id: t.id, result: '', receipt: '' }; hdlg.value = true }
async function submitHandle() {
  const { error } = await api.POST('/tickets/{id}/handle', { params: { path: { id: hForm.value.id } }, body: { result: hForm.value.result, receipt: hForm.value.receipt } as any })
  if (error) { ElMessage.error('处理失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('工单已处理'); hdlg.value = false; loadAll()
}
// 回款冲销
async function reverseRepay(r: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt('冲销原因（误标红冲 BR-M4-07）', '冲销回款', { inputValidator: (v) => !!v || '原因必填' })
    const { error } = await api.POST('/repay-lines/{id}/reverse', { params: { path: { id: r.id } }, body: { reason } as any })
    if (error) { ElMessage.error('冲销失败：' + ((error as any)?.message ?? '已入支付申请单不可冲')); return }
    ElMessage.success('已冲销'); loadAll()
  } catch { /* 取消 */ }
}
// 联系人 add / 失效
async function addContact() {
  try {
    const { value: phone } = await ElMessageBox.prompt('新增联系电话', '新增联系人', { inputValidator: (v) => /^\d{6,}$/.test(v) || '请输入有效号码' })
    const { error } = await api.POST('/cases/{id}/contacts', { params: { path: { id } }, body: { phone, label: '补充' } as any })
    if (error) { ElMessage.error('新增失败'); return }
    ElMessage.success('已新增联系人'); loadAll()
  } catch { /* 取消 */ }
}
async function invalidContact(c: any) {
  const { error } = await api.PATCH('/contacts/{id}', { params: { path: { id: String(c.id) } }, body: { invalid: true } as any })
  if (error) { ElMessage.error('标记失效失败'); return }
  ElMessage.success('已标记失效'); loadAll()
}
// 法务送达
async function deliverLegal(doc: any) {
  const { error } = await api.POST('/legal-docs/{id}/deliver', { params: { path: { id: String(doc.id) } }, body: { signedPhotoUrl: 'https://example.com/sign.jpg' } as any })
  if (error) { ElMessage.error('送达登记失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已登记送达'); loadAll()
}
// AI 采纳联动（校验动作权限）
const ADOPT: any = { PROMISE: ['promise', '登记承诺', 'case.promise'], TICKET: ['ticket', '转工单', 'case.ticket'], PAYLINK: ['paylink', '发缴费链接', 'case.paylink'], FOLLOWUP: ['follow', '写跟进', 'case.follow'] }
function adopt(card: any) {
  const m = ADOPT[card.actionRef]; if (!m) { ElMessage.info('该建议无联动动作'); return }
  if (!auth.has(m[2])) { ElMessage.warning('无权限：' + m[2]); return }
  openAct(m[0], m[1] + '（采纳 AI 建议）', card.id)
}
onMounted(loadAll)
</script>

<template>
  <div v-if="d">
    <el-page-header @back="router.back()" :content="`案件作业台：${d.case?.ownerName} ${d.case?.room} · ${d.case?.status}`" style="margin-bottom:12px" />
    <el-card style="margin-bottom:12px">
      <el-button v-if="auth.has('case.follow')" type="primary" size="small" @click="openAct('follow','写跟进')">写跟进</el-button>
      <el-button v-if="auth.has('case.promise')" size="small" @click="openAct('promise','登记承诺')">登记承诺</el-button>
      <el-button v-if="auth.has('case.ticket')" size="small" @click="openAct('ticket','转工单')">转工单</el-button>
      <el-button v-if="auth.has('case.paylink')" size="small" @click="openAct('paylink','发缴费链接')">发缴费链接</el-button>
      <el-button v-if="auth.has('case.repay.mark')" size="small" type="success" @click="openAct('repay','登记还款')">登记还款</el-button>
      <el-button v-if="auth.has('legal.create')" size="small" @click="openAct('legal','申请法务文书')">申请法务</el-button>
      <el-button v-if="auth.has('evidence.create')" size="small" @click="openAct('evidence','发起存证')">发起存证</el-button>
      <el-button v-if="auth.has('case.close')" size="small" type="danger" plain @click="openAct('close','结案')">结案</el-button>
    </el-card>

    <el-tabs>
      <el-tab-pane label="概览 / 联系人">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="户号">{{ d.case?.acctNo }}</el-descriptions-item>
          <el-descriptions-item label="应收">{{ yuan(d.case?.dueCents) }}</el-descriptions-item>
          <el-descriptions-item label="减免后">{{ yuan(d.case?.reduceAfterCents ?? d.case?.dueCents) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ d.case?.status }}</el-descriptions-item>
          <el-descriptions-item label="池">{{ d.case?.pool }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">联系方式 <el-button v-if="auth.has('case.follow')" size="small" text type="primary" @click="addContact">+ 新增</el-button></el-divider>
        <el-table :data="d.contacts ?? []" size="small" border>
          <el-table-column prop="phone" label="电话" /><el-table-column prop="label" label="标签" />
          <el-table-column label="状态"><template #default="{row}"><el-tag size="small" :type="row.invalid?'info':'success'">{{ row.invalid?'失效':'有效' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="!row.invalid && auth.has('case.follow')" size="small" text @click="invalidContact(row)">标失效</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="通话 / AI 复盘">
        <el-button size="small" v-if="auth.has('case.call')" @click="getLatest">获取最新通话录音</el-button>
        <el-button size="small" v-if="auth.has('case.call')" tag="label" style="margin-left:6px">上传录音<input type="file" hidden accept="audio/*" @change="uploadRecording" /></el-button>
        <template v-if="latest?.hasRecording && latest.recording">
          <el-descriptions :column="3" border size="small" style="margin-top:8px">
            <el-descriptions-item label="状态">{{ latest.recording.status }}</el-descriptions-item>
            <el-descriptions-item label="时长">{{ latest.recording.durationSec }}s</el-descriptions-item>
            <el-descriptions-item label="操作">
              <el-button size="small" type="primary" @click="loadReview(latest.recording.id)">看 AI 复盘</el-button>
              <el-button v-if="latest.recording.status==='FAILED'" size="small" @click="parseRec(latest.recording.id)">重新解析</el-button>
              <el-button v-if="auth.has('case.call')" size="small" @click="openMark(latest.recording.id)">标记结果</el-button>
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <template v-if="review">
          <el-divider>AI 复盘</el-divider>
          <p><b>小结：</b>{{ review.summary }}</p>
          <p v-if="review.risks?.length"><b>风险：</b><el-tag v-for="r in review.risks" :key="r.desc" type="danger" size="small" style="margin:2px">{{ r.level }} {{ r.desc }}</el-tag></p>
          <el-card v-for="su in review.suggestions ?? []" :key="su.id" shadow="never" style="margin:6px 0">
            <b>{{ su.title }}</b> <el-tag size="small">{{ su.type }}</el-tag>
            <div style="color:#606266;font-size:13px">{{ su.body }}</div>
            <el-button v-if="su.actionRef && su.actionRef!=='NONE'" size="small" type="primary" text @click="adopt(su)">采纳 → {{ su.actionRef }}</el-button>
          </el-card>
        </template>
      </el-tab-pane>

      <el-tab-pane label="承诺 / 工单">
        <el-divider content-position="left">承诺（含分期）</el-divider>
        <el-table :data="promises" size="small" border>
          <el-table-column prop="date" label="日期" /><el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
          <el-table-column prop="state" label="状态" /><el-table-column label="分期"><template #default="{row}">{{ row.installments?.length ? row.installments.length+' 期' : '单笔' }}</template></el-table-column>
        </el-table>
        <el-divider content-position="left">工单</el-divider>
        <el-table :data="tickets" size="small" border>
          <el-table-column prop="type" label="类型" /><el-table-column prop="note" label="说明" />
          <el-table-column prop="status" label="状态" width="90" /><el-table-column prop="result" label="结果" />
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="row.status==='PENDING' && auth.has('ticket.handle')" size="small" type="primary" @click="openHandle(row)">处理</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="回款">
        <el-table :data="repays" size="small" border>
          <el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
          <el-table-column prop="channel" label="渠道" /><el-table-column prop="paidAt" label="日期" />
          <el-table-column label="状态"><template #default="{row}"><el-tag size="small" :type="row.reversed?'info':(row.settled?'success':'warning')">{{ row.reversed?'已冲销':(row.settled?'已结':'未结') }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="!row.reversed && auth.has('case.repay.mark')" size="small" text type="danger" @click="reverseRepay(row)">冲销</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="法务 / 存证">
        <el-table :data="legalDocs" size="small" border>
          <el-table-column prop="type" label="文书类型" /><el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="deliveredAt" label="送达时间" />
          <el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="row.status!=='DELIVERED' && auth.has('legal.create')" size="small" @click="deliverLegal(row)">登记送达</el-button></template></el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:8px" title="存证：通过上方「发起存证」按场景(录音/送达/材料包)发起；列表与验真见「存证」菜单。" />
      </el-tab-pane>
    </el-tabs>

    <!-- 通用动作对话框 -->
    <el-dialog v-model="dlg.open" :title="dlg.title" width="500px">
      <el-form label-width="90px">
        <template v-if="dlg.kind==='follow'">
          <el-form-item label="方式"><el-select v-model="form.method"><el-option v-for="m in ['CALL','SMS','VISIT','WECHAT','OTHER']" :key="m" :label="m" :value="m" /></el-select></el-form-item>
          <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="3" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='promise'">
          <el-form-item label="承诺日期"><el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
          <el-form-item label="分期">
            <el-button size="small" @click="addInstallment">+ 加一期</el-button>
            <div v-for="(it,i) in form.installments" :key="i" style="margin-top:4px">
              第{{ it.seq }}期 <el-date-picker v-model="it.dueDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:140px" /> <el-input-number v-model="it.amountYuan" :min="0" size="small" />
            </div>
          </el-form-item>
        </template>
        <template v-else-if="dlg.kind==='ticket'">
          <el-form-item label="类型"><el-input v-model="form.type" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='repay'">
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option v-for="c in ['WECHAT_QR','BANK_TRANSFER','CASH']" :key="c" :label="c" :value="c" /></el-select></el-form-item>
          <el-form-item label="到账日"><el-date-picker v-model="form.paidAt" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='legal'">
          <el-form-item label="文书类型"><el-select v-model="form.type"><el-option label="催款函" value="COLLECTION_LETTER" /><el-option label="律师函" value="LAWYER_LETTER" /><el-option label="诉讼" value="LITIGATION" /></el-select></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='evidence'">
          <el-form-item label="存证场景"><el-select v-model="form.scene"><el-option label="录音" value="RECORDING" /><el-option label="送达" value="DELIVERY" /><el-option label="材料包" value="MATERIAL_PACK" /></el-select></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='close'">
          <el-form-item label="结案类型"><el-select v-model="form.closeKind"><el-option label="撤案" value="WITHDRAWN" /><el-option label="坏账" value="BAD_DEBT" /></el-select></el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option label="短信" value="SMS" /><el-option label="微信转发" value="WECHAT_COPY" /></el-select></el-form-item>
        </template>
        <el-form-item v-if="form.sourceSuggestionId"><el-tag type="success">采纳 AI 建议 #{{ form.sourceSuggestionId }}</el-tag></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg.open=false">取消</el-button><el-button type="primary" @click="submitAct">提交</el-button></template>
    </el-dialog>

    <!-- 工单处理 -->
    <el-dialog v-model="hdlg" title="处理工单（POST /tickets/{id}/handle）" width="440px">
      <el-form label-width="80px">
        <el-form-item label="处理结果"><el-input v-model="hForm.result" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="回执"><el-input v-model="hForm.receipt" placeholder="回执地址/说明" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="hdlg=false">取消</el-button><el-button type="primary" @click="submitHandle">提交</el-button></template>
    </el-dialog>

    <!-- 通话结果标记 -->
    <el-dialog v-model="mkdlg" title="通话结果标记（POST /recordings/{id}/ai-review）" width="400px">
      <el-form label-width="80px"><el-form-item label="结果码"><el-select v-model="mkForm.mark"><el-option v-for="m in ['PROMISED','REFUSED','NEED_TICKET','FOLLOW_UP','NO_ANSWER']" :key="m" :label="m" :value="m" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="mkdlg=false">取消</el-button><el-button type="primary" @click="submitMark">标记</el-button></template>
    </el-dialog>
  </div>
</template>
