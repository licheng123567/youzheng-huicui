<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M4 催收作业台：案件聚合详情 + 通话录音/AI复盘 + 跟进/承诺/工单/缴费动作(按 /me 权限门控)。
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const id = String(route.params.id)
const d = ref<any>(null)
const promises = ref<any[]>([])
const latest = ref<any>(null)
const review = ref<any>(null)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

async function loadAll() {
  const det = await api.GET('/cases/{id}', { params: { path: { id } } })
  if (det.error) { ElMessage.error('加载失败'); return }
  d.value = det.data
  const pr = await api.GET('/cases/{id}/promises', { params: { path: { id }, query: { page: 1, size: 20 } } } as any)
  promises.value = (pr.data as any)?.items ?? []
}
// 获取最新通话录音(查最近一通状态 BR-M4-01b)
async function getLatest() {
  const { data, error } = await api.GET('/cases/{id}/recordings/latest', { params: { path: { id } } })
  if (error) { ElMessage.error('获取失败'); return }
  latest.value = data
  if (!(data as any)?.hasRecording) ElMessage.info('暂无录音上来，可手动上传（App 通话结束自动上传 / 无则 Web 上传救济）')
}
// 看 AI 复盘
async function loadReview(recId: string) {
  const { data, error } = await api.GET('/recordings/{id}/ai-review', { params: { path: { id: recId } } })
  if (error) { ElMessage.error('复盘加载失败'); return }
  review.value = data
}

// 动作对话框
const dlg = ref<{ open: boolean; kind: string; title: string }>({ open: false, kind: '', title: '' })
const form = ref<any>({})
function openAct(kind: string, title: string, sourceSuggestionId?: string) {
  form.value = kind === 'follow' ? { content: '', method: 'CALL', sourceSuggestionId }
    : kind === 'promise' ? { date: '', amountYuan: 0, sourceSuggestionId }
    : kind === 'ticket' ? { type: '上门核实', note: '', sourceSuggestionId }
    : kind === 'close' ? { closeKind: 'WITHDRAWN', reason: '' }
    : { channel: 'SMS', sourceSuggestionId }
  dlg.value = { open: true, kind, title }
}
async function submitAct() {
  const k = dlg.value.kind, f = form.value
  let res: any
  if (k === 'follow') res = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: f.content, method: f.method, sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'promise') res = await api.POST('/cases/{id}/promises', { params: { path: { id } }, body: { date: f.date, amountCents: Math.round(f.amountYuan * 100), sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'ticket') res = await api.POST('/cases/{id}/tickets', { params: { path: { id } }, body: { type: f.type, note: f.note, sourceSuggestionId: f.sourceSuggestionId } as any })
  else if (k === 'close') res = await api.POST('/cases/{id}/close', { params: { path: { id } }, body: { kind: f.closeKind, reason: f.reason } as any })
  else res = await api.POST('/cases/{id}/pay-links', { params: { path: { id } }, body: { channel: f.channel, sourceSuggestionId: f.sourceSuggestionId } as any })
  if (res.error) { ElMessage.error('提交失败：' + ((res.error as any)?.message ?? '')); return }
  ElMessage.success(dlg.value.title + '成功'); dlg.value.open = false; loadAll()
}
// AI 建议采纳联动：按 actionRef 打开对应动作对话框 + 带 sourceSuggestionId。
// 同时校验对应动作权限(不绕过具体动作授权)。
const ADOPT_MAP: any = {
  PROMISE: ['promise', '登记承诺', 'case.promise'], TICKET: ['ticket', '转工单', 'case.ticket'],
  PAYLINK: ['paylink', '发缴费链接', 'case.paylink'], FOLLOWUP: ['follow', '写跟进', 'case.follow'],
}
function adopt(card: any) {
  const m = ADOPT_MAP[card.actionRef]
  if (!m) { ElMessage.info('该建议无联动动作'); return }
  if (!auth.has(m[2])) { ElMessage.warning('无权限执行该动作：' + m[2]); return }
  openAct(m[0], m[1] + '（采纳 AI 建议）', card.id)
}
onMounted(loadAll)
</script>

<template>
  <div v-if="d">
    <el-page-header @back="router.back()" :content="`案件作业台：${d.case?.ownerName} ${d.case?.room}`" style="margin-bottom:12px" />
    <!-- 操作工具栏(按权限门控) -->
    <el-card style="margin-bottom:12px">
      <el-button v-if="auth.has('case.follow')" type="primary" @click="openAct('follow','写跟进')">写跟进</el-button>
      <el-button v-if="auth.has('case.promise')" @click="openAct('promise','登记承诺')">登记承诺</el-button>
      <el-button v-if="auth.has('case.ticket')" @click="openAct('ticket','转工单')">转工单</el-button>
      <el-button v-if="auth.has('case.paylink')" @click="openAct('paylink','发缴费链接')">发缴费链接</el-button>
      <el-button v-if="auth.has('case.call')" @click="getLatest">获取最新通话录音</el-button>
      <el-button v-if="auth.has('case.close')" type="danger" plain @click="openAct('close','结案')">结案（撤案/坏账）</el-button>
    </el-card>

    <el-row :gutter="12">
      <el-col :span="12">
        <el-card header="案件信息">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="户号">{{ d.case?.acctNo }}</el-descriptions-item>
            <el-descriptions-item label="应收">{{ yuan(d.case?.dueCents) }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ d.case?.status }} / {{ d.case?.pool }}</el-descriptions-item>
          </el-descriptions>
          <el-divider>联系方式</el-divider>
          <el-table :data="d.contacts ?? []" size="small" border>
            <el-table-column prop="phone" label="电话" /><el-table-column prop="label" label="标签" />
          </el-table>
          <el-divider>承诺</el-divider>
          <el-table :data="promises" size="small" border>
            <el-table-column prop="date" label="日期" /><el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
            <el-table-column prop="state" label="状态" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="通话录音 / AI 复盘（BR-M4-01b：查最近一通状态）">
          <el-empty v-if="!latest" description="点「获取最新通话录音」" :image-size="50" />
          <template v-else-if="latest.hasRecording && latest.recording">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="状态">{{ latest.recording.status }}</el-descriptions-item>
              <el-descriptions-item label="时长">{{ latest.recording.durationSec }}s</el-descriptions-item>
            </el-descriptions>
            <el-button size="small" type="primary" style="margin-top:8px" @click="loadReview(latest.recording.id)">看 AI 复盘</el-button>
          </template>
          <el-alert v-else type="warning" :closable="false" title="暂无录音上来（手动上传救济）" />

          <template v-if="review">
            <el-divider>AI 复盘</el-divider>
            <p><b>小结：</b>{{ review.summary }}</p>
            <p v-if="review.risks?.length"><b>风险：</b><el-tag v-for="r in review.risks" :key="r.desc" type="danger" size="small" style="margin:2px">{{ r.level }} {{ r.desc }}</el-tag></p>
            <b>建议（可采纳联动）：</b>
            <el-card v-for="s in review.suggestions ?? []" :key="s.id" shadow="never" style="margin:6px 0">
              <div><b>{{ s.title }}</b> <el-tag size="small">{{ s.type }}</el-tag></div>
              <div style="color:#606266;font-size:13px">{{ s.body }}</div>
              <el-button v-if="s.actionRef && s.actionRef!=='NONE'" size="small" type="primary" text @click="adopt(s)">采纳 → {{ s.actionRef }}</el-button>
            </el-card>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <!-- 动作对话框 -->
    <el-dialog v-model="dlg.open" :title="dlg.title" width="460px">
      <el-form label-width="90px">
        <template v-if="dlg.kind==='follow'">
          <el-form-item label="方式"><el-select v-model="form.method"><el-option v-for="m in ['CALL','SMS','VISIT','WECHAT','OTHER']" :key="m" :label="m" :value="m" /></el-select></el-form-item>
          <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="3" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='promise'">
          <el-form-item label="承诺日期"><el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="金额(元)"><el-input-number v-model="form.amountYuan" :min="0" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='ticket'">
          <el-form-item label="类型"><el-input v-model="form.type" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
        </template>
        <template v-else-if="dlg.kind==='close'">
          <el-form-item label="结案类型"><el-select v-model="form.closeKind"><el-option label="撤案 WITHDRAWN" value="WITHDRAWN" /><el-option label="坏账 BAD_DEBT" value="BAD_DEBT" /></el-select></el-form-item>
          <el-form-item label="原因"><el-input v-model="form.reason" type="textarea" :rows="2" placeholder="受控原因，仅留痕、不流转审批" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="渠道"><el-select v-model="form.channel"><el-option label="短信" value="SMS" /><el-option label="微信转发" value="WECHAT_COPY" /></el-select></el-form-item>
        </template>
        <el-form-item v-if="form.sourceSuggestionId"><el-tag type="success">采纳 AI 建议 #{{ form.sourceSuggestionId }}</el-tag></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg.open=false">取消</el-button><el-button type="primary" @click="submitAct">提交</el-button></template>
    </el-dialog>
  </div>
</template>
