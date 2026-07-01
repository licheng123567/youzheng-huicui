<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { payReqStatusLabel, channelLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// M9 结算·资金双线：side=IN(收佣 平台↔物业)/OUT(付佣 平台↔服务商)。
// 生成链：勾选未结回款明细→生成支付申请单→发送→详情→完成(凭证)/撤销。内催佣金链独立面板。
const auth = useAuth()
const role = computed(() => auth.me?.role)
// CO(催收员)不看组织级对账/支付申请单(US-M9-09 仅本人佣金只读)，只看「我的佣金」面板。
const canViewPayReq = computed(() => role.value !== 'CO')
const sides = computed(() => (role.value === 'PL' || role.value === 'PC') ? ['IN'] : (role.value === 'VL') ? ['OUT'] : ['IN', 'OUT'])
// 平台角色：SA/SE；服务商角色：VL/CO
const isPlatform = computed(() => role.value === 'SA' || role.value === 'SE')
const isProvider = computed(() => role.value === 'VL' || role.value === 'CO')
// 契约 x-settlement-side-rule: IN→平台(SA/SE)生成；OUT→服务商(VL)生成；generatedBy 服务端派生
// canGenerate: 生成支付申请单权限（payreq.create + 线别与角色匹配）
const canGenerate = computed(() => auth.has('payreq.create') && ((isPlatform.value && side.value === 'IN') || (isProvider.value && side.value === 'OUT')))
// canRevoke: 撤销权限同口径——生成方才能撤销（与 canGenerate 同条件）
const canRevoke = computed(() => auth.has('payreq.create') && ((isPlatform.value && side.value === 'IN') || (isProvider.value && side.value === 'OUT')))
// canComplete: 完成恒由平台受理(契约 completePaymentRequest x-data-scope=platform)——IN=平台确认收款 / OUT=平台支付+上传凭证(BR-M9-12)；仅 SA/SE 持 payreq.complete
const canComplete = computed(() => auth.has('payreq.complete') && isPlatform.value)
// H-01：PL/PC 物业角色对收佣支付申请单为「◐只读本物业」——可见但无生成/完成/撤销(读靠后端 scope+side-rule 裁剪 IN 线)。
const isReadonlyProperty = computed(() => role.value === 'PL' || role.value === 'PC')
const side = ref<'IN' | 'OUT'>('IN')
const rollup = ref<any[]>([]); const prs = ref<any[]>([]); const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(2) + '%')

async function load() {
  if (!canViewPayReq.value) return   // CO 仅「我的佣金」
  loading.value = true
  const rk = await api.GET('/recon/rollup', { params: { query: { side: side.value, page: 1, size: 20 } } as any })
  rollup.value = (rk.data as any)?.items ?? []
  const pr = await api.GET('/payment-requests', { params: { query: { side: side.value, page: 1, size: 20 } } as any })
  loading.value = false
  if (pr.error) { ElMessage.error('加载支付申请单失败（可能跨线 403）'); prs.value = []; return }
  prs.value = (pr.data as any)?.items ?? []
}

// ── 生成支付申请单：勾选批次未结回款明细 → POST /payment-requests ──
const gdlg = ref(false); const gBatchId = ref(''); const gLines = ref<any[]>([]); const gSel = ref<any[]>([])
function openGenerate() { gBatchId.value = ''; gLines.value = []; gSel.value = []; gdlg.value = true }
async function loadLines() {
  if (!gBatchId.value) { ElMessage.warning('请填批次 id'); return }
  const { data, error } = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: gBatchId.value }, query: { page: 1, size: 100 } } as any })
  if (error) { ElMessage.error('加载回款明细失败'); return }
  // 仅可组单：未结清且未入单
  gLines.value = ((data as any)?.items ?? []).filter((l: any) => !l.settled && !l.paymentRequestId)
  if (!gLines.value.length) ElMessage.info('该批次无可组单的未结回款明细')
}
async function submitGenerate() {
  if (!gSel.value.length) { ElMessage.warning('请勾选明细'); return }
  const { error } = await api.POST('/payment-requests', { body: { side: side.value, batchId: gBatchId.value, lineIds: gSel.value.map((l) => String(l.id)) } as any })
  if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '可能错线/防倒挂')); return }
  ElMessage.success(`已生成支付申请单（${gSel.value.length} 笔明细）`); gdlg.value = false; load()
}

// 发送
async function send(row: any) {
  const { error } = await api.POST('/payment-requests/{id}/send', { params: { path: { id: row.id } } } as any)
  if (error) { ElMessage.error('发送失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已发送'); load()
}
// 详情
const ddlg = ref(false); const detail = ref<any>(null)
async function openDetail(row: any) {
  const { data, error } = await api.GET('/payment-requests/{id}', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('详情加载失败'); return }
  detail.value = data; ddlg.value = true
}
// 完成（必带凭证）。fileUrl 应为上传所得地址：el-upload 选文件后置 fileUrl；无真实上传后端时保留可手填 URL，默认空且必填校验(BR-M9-12d)。
const cdlg = ref(false); const cform = ref<any>({ fileUrl: '' })
function openComplete(row: any) { cform.value = { id: row.id, version: row.version, type: side.value === 'IN' ? 'RECEIPT' : 'PAYMENT', fileUrl: '' }; cdlg.value = true }
// el-upload 占位：选择文件后取上传所得地址(response.url/fileUrl)回填；后端未接真实上传时退化为文件名占位，仍可手填覆盖。
function onVoucherUpload(resp: any, file: any) {
  const url = resp?.url ?? resp?.fileUrl ?? resp?.data?.url
  cform.value.fileUrl = url ?? file?.name ?? ''
  if (url) ElMessage.success('凭证已上传')
}
function onVoucherUploadError() { ElMessage.error('上传失败，可手填凭证 URL') }
async function submitComplete() {
  if (!cform.value.fileUrl) { ElMessage.warning('请上传凭证或填写凭证文件 URL（必填）'); return }
  const { error } = await api.POST('/payment-requests/{id}/complete', { params: { path: { id: cform.value.id } }, body: { voucher: { type: cform.value.type, fileUrl: cform.value.fileUrl }, version: cform.value.version } as any })
  if (error) { ElMessage.error('完成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已完成（凭证已留存）'); cdlg.value = false; load()
}
async function revoke(row: any) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(`请填写${side.value === 'IN' ? '撤销' : '撤回'}原因`, side.value === 'IN' ? '撤销支付申请单' : '撤回支付申请单', {
      inputPlaceholder: '撤销原因（必填）',
      inputValidator: (v: string) => (!!v && v.trim().length > 0) || '撤销原因不能为空',
    })
    reason = String(value).trim()
  } catch { return /* 取消 */ }
  const { error } = await api.POST('/payment-requests/{id}/revoke', { params: { path: { id: row.id } }, body: { version: row.version, reason } as any })
  if (error) { ElMessage.error('撤销失败：' + ((error as any)?.message ?? '已PAID不可撤')); return }
  ElMessage.success('已撤销，明细释放'); load()
}

// ── 内催佣金链（服务商内部：cocomm.manage 看名册/生成佣金单/确认支付；CO 自查 me/settlement）──
const coco = ref<any[]>([]); const coDocs = ref<any[]>([]); const mySettle = ref<any>(null)
const showCoComm = computed(() => auth.has('cocomm.manage'))
async function loadCoComm() {
  if (auth.has('cocomm.manage')) {
    coco.value = ((await api.GET('/co-commissions', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
    coDocs.value = ((await api.GET('/co-pay-docs', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
  }
  if (auth.has('cocomm.self.view')) {
    mySettle.value = (await api.GET('/me/settlement', {})).data
  }
}
async function confirmPay(doc: any) {
  const { error } = await api.POST('/co-pay-docs/{id}/confirm-pay', { params: { path: { id: doc.id } } } as any)
  if (error) { ElMessage.error('确认支付失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已确认支付'); loadCoComm()
}
// 设催收员某批次佣金比例（PUT·防倒挂后端校验）
async function setRate(c: any) {
  try {
    const { value } = await ElMessageBox.prompt(`设 ${c.name} 某批次佣金比例（输入 批次id,比例 如 1,0.15）`, '设佣金比例', { inputValidator: (v: string) => /^\d+,0?\.\d+$/.test(v) || '格式 批次id,分数(0-1)' })
    const [bid, rate] = String(value).split(',')
    const { error } = await api.PUT('/co-commissions/{collectorId}/batches/{batchId}/rate', { params: { path: { collectorId: String(c.collectorId), batchId: bid } }, body: { rate: Number(rate) } as any })
    if (error) { ElMessage.error('设比例失败：' + ((error as any)?.message ?? '可能防倒挂')); return }
    ElMessage.success('已设佣金比例'); loadCoComm()
  } catch { /* 取消 */ }
}
// ── M-05 内催佣金穿透组单：催收员 → 批次(比例) → 该批未结回款明细勾选 → POST /co-pay-docs ──
// 数据源：GET /co-commissions/{collectorId}/batches 列批次；选批次后 GET /batches/{id}/repay-lines 取明细，过滤未结/未入单。
const cdlg2 = ref(false)            // 组单弹窗
const cCollector = ref<any>(null)   // 当前催收员
const cBatches = ref<any[]>([])     // 该催收员各批次(含比例)
const cBatchId = ref('')            // 选中批次
const cLines = ref<any[]>([])       // 选中批次的未结明细
const cSel = ref<any[]>([])         // 勾选明细
const cLoading = ref(false)
async function openGenCoPayDoc(c: any) {
  cCollector.value = c; cBatchId.value = ''; cLines.value = []; cSel.value = []; cBatches.value = []
  cdlg2.value = true
  cLoading.value = true
  const { data, error } = await api.GET('/co-commissions/{collectorId}/batches', { params: { path: { collectorId: String(c.collectorId) } } })
  cLoading.value = false
  if (error) { ElMessage.error('加载批次失败'); return }
  cBatches.value = (data as any) ?? []
}
async function loadCoLines() {
  if (!cBatchId.value) { ElMessage.warning('请先选择批次'); return }
  cSel.value = []
  cLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: cBatchId.value }, query: { page: 1, size: 100 } } as any })
  cLoading.value = false
  if (error) { ElMessage.error('加载回款明细失败'); cLines.value = []; return }
  // 仅可组单：未结清且未入单（内催佣金按未结明细组单 BR-M9-19）
  cLines.value = ((data as any)?.items ?? []).filter((l: any) => !l.settled && !l.paymentRequestId)
  if (!cLines.value.length) ElMessage.info('该批次无可组单的未结回款明细')
}
async function submitGenCoPayDoc() {
  if (!cSel.value.length) { ElMessage.warning('请勾选明细'); return }
  const { error } = await api.POST('/co-pay-docs', { body: { collectorId: String(cCollector.value.collectorId), lineIds: cSel.value.map((l) => String(l.id)) } as any })
  if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已生成佣金支付单（${cSel.value.length} 笔明细 · PENDING_PAY）`); cdlg2.value = false; loadCoComm()
}

// ── M-05 佣金单详情：GET /co-pay-docs/{id} 展示 lines 穿透快照 ──
const codDlg = ref(false); const coDetail = ref<any>(null)
async function openCoDocDetail(row: any) {
  const { data, error } = await api.GET('/co-pay-docs/{id}', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('佣金单详情加载失败（可能已删除）'); return }
  coDetail.value = data; codDlg.value = true
}
onMounted(() => { side.value = sides.value[0] as any; load(); loadCoComm() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>结算 · 资金双线</div>
      <div class="ops"><span class="note" style="margin:0">IN 收佣 平台↔物业 / OUT 付佣 平台↔服务商</span></div>
    </div>

   <template v-if="canViewPayReq">
    <div v-if="isReadonlyProperty" class="alert info" style="margin-top:0;margin-bottom:14px">
      <b>只读视图：</b>物业仅可查看本物业收佣线(IN)对账与支付申请单，不可生成/确认/撤销。
    </div>

    <div class="toolbar">
      <span class="segctrl">
        <span v-for="s in sides" :key="s" :class="{ on: side === s }" @click="side = s; load()">{{ s==='IN'?'收佣线(IN)':'付佣线(OUT)' }}</span>
      </span>
      <button v-if="canGenerate" class="btn sm" @click="openGenerate">勾选明细生成支付申请单</button>
    </div>

    <div class="sec-title">对账汇总（GET /recon/rollup）</div>
    <!-- M-10：契约 ReconRollup 字段 batch/proj/baseCents/repayRate/commRate/dueCents/settledCents/unsettledCents -->
    <table>
      <thead>
        <tr>
          <th>批次</th><th>项目</th><th>回款基数</th><th>回款率</th><th>比例</th><th>应结</th><th>已结</th><th>未结</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rollup" :key="row.batch">
          <td><b>{{ row.batch }}</b></td>
          <td>{{ row.proj }}</td>
          <td class="num">{{ yuan(row.baseCents) }}</td>
          <td class="num">{{ pct(row.repayRate) }}</td>
          <td class="num">{{ pct(row.commRate) }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td class="num"><span class="tag suc">{{ yuan(row.settledCents) }}</span></td>
          <td class="num"><span class="tag" :class="row.unsettledCents ? 'war' : 'inf'">{{ yuan(row.unsettledCents) }}</span></td>
        </tr>
        <tr v-if="!rollup.length"><td colspan="8" class="note" style="text-align:center">暂无对账数据</td></tr>
      </tbody>
    </table>

    <div class="sec-title">支付申请单（GET /payment-requests?side={{side}}）</div>
    <table v-loading="loading">
      <thead>
        <tr>
          <th>单号</th><th style="width:90px">状态</th><th>基数</th><th>比例</th><th>应结佣金</th><th style="width:300px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in prs" :key="row.id">
          <td>{{ row.code }}</td>
          <td><span class="tag" :class="row.status==='PAID'?'suc':row.status==='VOIDED'?'inf':'war'" :title="row.status">{{ payReqStatusLabel(row.status) }}</span></td>
          <td class="num">{{ yuan(row.baseCents) }}</td>
          <td class="num">{{ pct(row.commRate) }}</td>
          <td class="num">{{ yuan(row.commCents) }}</td>
          <td>
            <button class="btn txt" @click="openDetail(row)">详情</button>
            <!-- N-01：单据下载入口(documentUrl 空则禁用·占位) -->
            <a v-if="row.documentUrl" class="link" :href="row.documentUrl" target="_blank" style="margin:0 8px;font-size:13px">下载</a>
            <button v-else class="btn txt" disabled>下载</button>
            <template v-if="row.status==='PENDING'">
              <button v-if="canGenerate" class="btn txt" @click="send(row)">发送</button>
              <button v-if="canComplete" class="btn txt" @click="openComplete(row)">{{ side==='IN'?'确认收款':'支付完成' }}</button>
              <button v-if="canRevoke" class="btn txt" @click="revoke(row)">{{ side==='IN'?'撤销':'撤回' }}</button>
            </template>
          </td>
        </tr>
        <tr v-if="!loading && !prs.length"><td colspan="6" class="note" style="text-align:center">暂无支付申请单</td></tr>
      </tbody>
    </table>
   </template>

    <!-- 内催佣金链 -->
    <template v-if="showCoComm">
      <div class="sec-title">内催佣金（GET /co-commissions · 服务商内部催收员佣金）</div>
      <table>
        <thead>
          <tr>
            <th>催收员</th><th style="width:80px">批次数</th><th>应结</th><th>已结</th><th>未结</th><th style="width:180px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in coco" :key="row.collectorId">
            <td>{{ row.name }}</td>
            <td class="num">{{ row.batchCount }}</td>
            <td class="num">{{ yuan(row.dueCents) }}</td>
            <td class="num"><span class="tag suc">{{ yuan(row.settledCents) }}</span></td>
            <td class="num"><span class="tag" :class="row.unsettledCents ? 'war' : 'inf'">{{ yuan(row.unsettledCents) }}</span></td>
            <td>
              <button class="btn txt" @click="setRate(row)">设比例</button>
              <button class="btn txt" @click="openGenCoPayDoc(row)">生成佣金单</button>
            </td>
          </tr>
          <tr v-if="!coco.length"><td colspan="6" class="note" style="text-align:center">暂无催收员佣金</td></tr>
        </tbody>
      </table>
      <div class="note">佣金支付单（GET /co-pay-docs · PENDING_PAY → 确认支付 → SETTLED）</div>
      <table>
        <thead>
          <tr>
            <th>催收员</th><th style="width:70px">笔数</th><th>金额</th><th style="width:120px">状态</th><th style="width:180px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in coDocs" :key="row.id">
            <td>{{ row.collectorName }}</td>
            <td class="num">{{ row.count }}</td>
            <td class="num">{{ yuan(row.amountCents) }}</td>
            <td><span class="tag" :class="row.status==='SETTLED'?'suc':'war'">{{ row.status==='SETTLED'?'已结':'待支付' }}</span></td>
            <td>
              <button class="btn txt" @click="openCoDocDetail(row)">详情</button>
              <button v-if="row.status==='PENDING_PAY'" class="btn txt" @click="confirmPay(row)">确认支付</button>
            </td>
          </tr>
          <tr v-if="!coDocs.length"><td colspan="5" class="note" style="text-align:center">暂无佣金支付单</td></tr>
        </tbody>
      </table>
    </template>
    <template v-if="mySettle">
      <div class="sec-title">我的佣金（GET /me/settlement · 催收员自查）</div>
      <div class="kpis" style="grid-template-columns:repeat(3,1fr)">
        <div class="kpi"><div class="n">{{ yuan(mySettle.totalCents ?? mySettle.dueCents) }}</div><div class="l">应结</div></div>
        <div class="kpi"><div class="n" style="color:var(--success)">{{ yuan(mySettle.settledCents) }}</div><div class="l">已结</div></div>
        <div class="kpi"><div class="n" style="color:var(--warning)">{{ yuan(mySettle.unsettledCents) }}</div><div class="l">未结</div></div>
      </div>
    </template>

    <!-- 生成支付申请单 -->
    <el-dialog v-model="gdlg" :title="`勾选明细生成支付申请单（${side==='IN'?'收佣':'付佣'}线 POST /payment-requests）`" width="720px">
      <el-form :inline="true"><el-form-item label="批次 id"><el-input v-model="gBatchId" style="width:200px" placeholder="批次 ID（字符串）" /></el-form-item><el-button @click="loadLines">加载未结回款明细</el-button></el-form>
      <el-table :data="gLines" border size="small" @selection-change="(v:any)=>gSel=v" max-height="320">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
        <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column label="渠道"><template #default="{row}"><span :title="row.channel">{{ channelLabel(row.channel) }}</span></template></el-table-column><el-table-column prop="paidAt" label="日期" />
      </el-table>
      <div style="margin-top:6px;color:#606266">已选 {{ gSel.length }} 笔，合计 {{ yuan(gSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
      <template #footer><el-button @click="gdlg=false">取消</el-button><el-button type="primary" :disabled="!gSel.length" @click="submitGenerate">生成</el-button></template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="ddlg" title="支付申请单详情（GET /payment-requests/{id}）" width="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.code }}</el-descriptions-item>
          <el-descriptions-item label="状态"><span :title="detail.status">{{ payReqStatusLabel(detail.status) }}</span></el-descriptions-item>
          <el-descriptions-item label="基数">{{ yuan(detail.baseCents) }}</el-descriptions-item>
          <el-descriptions-item label="比例">{{ pct(detail.commRate) }}</el-descriptions-item>
          <el-descriptions-item label="应结佣金">{{ yuan(detail.commCents) }}</el-descriptions-item>
          <el-descriptions-item label="凭证">{{ detail.voucher?.fileUrl ?? '—' }}</el-descriptions-item>
          <!-- N-01：单据下载 + 电子签章占位(文件通道/签章方案 TBD) -->
          <el-descriptions-item label="单据下载">
            <el-link v-if="detail.documentUrl" type="primary" :href="detail.documentUrl" target="_blank">下载单据</el-link>
            <span v-else style="color:#909399">单据通道 TBD</span>
          </el-descriptions-item>
          <el-descriptions-item label="电子签章">
            <el-tag size="small" :type="detail.sealed ? 'success' : 'info'">{{ detail.sealed ? '已签章' : '未签章' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>明细快照（lines）</el-divider>
        <el-table :data="detail.lines ?? []" border size="small" max-height="240">
          <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents ?? row.amountCents) }}</template></el-table-column>
          <el-table-column label="佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- 完成 -->
    <DsDrawer v-model="cdlg" :title="(side==='IN'?'确认收款':'支付完成')" :width="440">
      <el-form label-width="100px">
        <el-form-item label="凭证类型"><el-tag>{{ cform.type==='RECEIPT'?'收款凭证':'支付凭证' }}</el-tag></el-form-item>
        <el-form-item label="上传凭证" required>
          <el-upload action="/api/uploads" :show-file-list="false" :on-success="onVoucherUpload"
            :on-error="onVoucherUploadError">
            <el-button size="small">选择文件上传</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="凭证文件 URL" required>
          <el-input v-model="cform.fileUrl" placeholder="上传后自动回填，或手填可访问地址（必填）" />
        </el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="cform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitComplete">完成</el-button></template>
    </DsDrawer>

    <!-- M-05 内催佣金穿透组单：催收员 → 批次(比例) → 未结明细勾选 → POST /co-pay-docs -->
    <el-dialog v-model="cdlg2" :title="`生成佣金单 · ${cCollector?.name ?? ''}（人→批次→明细勾选 POST /co-pay-docs）`" width="760px">
      <el-form :inline="true">
        <el-form-item label="批次">
          <el-select v-model="cBatchId" style="width:320px" placeholder="选择该催收员的批次" @change="loadCoLines">
            <el-option v-for="b in cBatches" :key="b.batchId" :value="b.batchId"
              :label="`${b.batchName ?? b.batchId}（比例 ${pct(b.rate)} · 未结 ${yuan(b.unsettledCents)} · ${b.unsettledLineCount ?? 0} 笔）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table v-loading="cLoading" :data="cLines" border size="small" @selection-change="(v:any)=>cSel=v" max-height="320">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
        <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column label="渠道"><template #default="{row}"><span :title="row.channel">{{ channelLabel(row.channel) }}</span></template></el-table-column><el-table-column prop="paidAt" label="日期" />
      </el-table>
      <div style="margin-top:6px;color:#606266">已选 {{ cSel.length }} 笔，合计回款 {{ yuan(cSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
      <template #footer><el-button @click="cdlg2=false">取消</el-button><el-button type="primary" :disabled="!cSel.length" @click="submitGenCoPayDoc">生成佣金单</el-button></template>
    </el-dialog>

    <!-- M-05 佣金单详情：GET /co-pay-docs/{id} lines 穿透快照 -->
    <el-dialog v-model="codDlg" title="佣金支付单详情（GET /co-pay-docs/{id}）" width="640px">
      <template v-if="coDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="催收员">{{ coDetail.collectorName ?? coDetail.collectorId }}</el-descriptions-item>
          <el-descriptions-item label="笔数">{{ coDetail.count ?? (coDetail.lines?.length ?? 0) }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ yuan(coDetail.amountCents) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ coDetail.status==='SETTLED'?'已结':'待支付' }}</el-descriptions-item>
          <!-- N-01 占位：佣金单下载 + 电子签章 -->
          <el-descriptions-item label="单据下载">
            <el-link v-if="coDetail.documentUrl" type="primary" :href="coDetail.documentUrl" target="_blank">下载单据</el-link>
            <span v-else style="color:#909399">单据通道 TBD</span>
          </el-descriptions-item>
          <el-descriptions-item label="电子签章">
            <el-tag size="small" :type="coDetail.sealed ? 'success' : 'info'">{{ coDetail.sealed ? '已签章' : '未签章' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>明细快照（lines · 催收员→批次→案件回款）</el-divider>
        <el-table :data="coDetail.lines ?? []" border size="small" max-height="240">
          <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents) }}</template></el-table-column>
          <el-table-column label="佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>
