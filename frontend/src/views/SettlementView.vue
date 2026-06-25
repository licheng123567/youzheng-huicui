<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M9 结算·资金双线：side=IN(收佣 平台↔物业)/OUT(付佣 平台↔服务商)。
// 生成链：勾选未结回款明细→生成支付申请单→发送→详情→完成(凭证)/撤销。内催佣金链独立面板。
const auth = useAuth()
const role = computed(() => auth.me?.role)
// CO(催收员)不看组织级对账/支付申请单(US-M9-09 仅本人佣金只读)，只看「我的佣金」面板。
const canViewPayReq = computed(() => role.value !== 'CO')
const sides = computed(() => (role.value === 'PL' || role.value === 'PC') ? ['IN'] : (role.value === 'VL') ? ['OUT'] : ['IN', 'OUT'])
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
// 完成（必带凭证）
const cdlg = ref(false); const cform = ref<any>({})
function openComplete(row: any) { cform.value = { id: row.id, version: row.version, type: side.value === 'IN' ? 'RECEIPT' : 'PAYMENT', fileUrl: 'https://example.com/voucher.pdf' }; cdlg.value = true }
async function submitComplete() {
  const { error } = await api.POST('/payment-requests/{id}/complete', { params: { path: { id: cform.value.id } }, body: { voucher: { type: cform.value.type, fileUrl: cform.value.fileUrl }, version: cform.value.version } as any })
  if (error) { ElMessage.error('完成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已完成（凭证已留存）'); cdlg.value = false; load()
}
async function revoke(row: any) {
  const { error } = await api.POST('/payment-requests/{id}/revoke', { params: { path: { id: row.id } }, body: { version: row.version, reason: '前端撤销' } as any })
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
// 生成佣金支付单（POST·lineIds 为该催收员未结内催回款明细 id，从批次回款明细处获取）
async function genCoPayDoc(c: any) {
  try {
    const { value } = await ElMessageBox.prompt(`为 ${c.name} 生成佣金单（输入未结明细 id，逗号分隔）`, '生成佣金支付单', { inputValidator: (v: string) => /^[\d,]+$/.test(v) || '逗号分隔的明细 id' })
    const lineIds = String(value).split(',').map((x) => x.trim()).filter(Boolean)
    const { error } = await api.POST('/co-pay-docs', { body: { collectorId: String(c.collectorId), lineIds } as any })
    if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已生成佣金支付单（PENDING_PAY）'); loadCoComm()
  } catch { /* 取消 */ }
}
onMounted(() => { side.value = sides.value[0] as any; load(); loadCoComm() })
</script>

<template>
  <el-card header="结算 · 资金双线（IN 收佣 平台↔物业 / OUT 付佣 平台↔服务商）">
   <template v-if="canViewPayReq">
    <el-radio-group v-model="side" style="margin-bottom:12px" @change="load">
      <el-radio-button v-for="s in sides" :key="s" :label="s">{{ s==='IN'?'收佣线(IN)':'付佣线(OUT)' }}</el-radio-button>
    </el-radio-group>
    <el-button v-if="auth.has('payreq.create')" type="primary" size="small" style="margin-left:12px" @click="openGenerate">勾选明细生成支付申请单</el-button>

    <el-divider content-position="left">对账汇总（GET /recon/rollup）</el-divider>
    <el-table :data="rollup" border size="small">
      <el-table-column prop="batchCode" label="批次" /><el-table-column label="回款基数"><template #default="{row}">{{ yuan(row.baseCents) }}</template></el-table-column>
      <el-table-column label="比例"><template #default="{row}">{{ pct(row.rate ?? row.commRate) }}</template></el-table-column>
      <el-table-column label="应结"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
    </el-table>

    <el-divider content-position="left">支付申请单（GET /payment-requests?side={{side}}）</el-divider>
    <el-table v-loading="loading" :data="prs" border size="small">
      <el-table-column prop="code" label="单号" />
      <el-table-column prop="status" label="状态" width="90"><template #default="{row}"><el-tag size="small" :type="row.status==='PAID'?'success':row.status==='VOIDED'?'info':'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="基数"><template #default="{row}">{{ yuan(row.baseCents) }}</template></el-table-column>
      <el-table-column label="比例"><template #default="{row}">{{ pct(row.commRate) }}</template></el-table-column>
      <el-table-column label="应结佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button size="small" text @click="openDetail(row)">详情</el-button>
          <template v-if="row.status==='PENDING'">
            <el-button v-if="auth.has('payreq.create')" size="small" @click="send(row)">发送</el-button>
            <el-button v-if="auth.has('payreq.complete')" size="small" type="primary" @click="openComplete(row)">{{ side==='IN'?'确认收款':'支付完成' }}</el-button>
            <el-button v-if="auth.has('payreq.create')" size="small" @click="revoke(row)">{{ side==='IN'?'撤销':'撤回' }}</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
   </template>

    <!-- 内催佣金链 -->
    <template v-if="showCoComm">
      <el-divider content-position="left">内催佣金（GET /co-commissions · 服务商内部催收员佣金）</el-divider>
      <el-table :data="coco" border size="small">
        <el-table-column prop="name" label="催收员" /><el-table-column prop="batchCount" label="批次数" width="80" />
        <el-table-column label="应结"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
        <el-table-column label="已结"><template #default="{row}">{{ yuan(row.settledCents) }}</template></el-table-column>
        <el-table-column label="未结"><template #default="{row}">{{ yuan(row.unsettledCents) }}</template></el-table-column>
        <el-table-column label="操作" width="170"><template #default="{row}">
          <el-button size="small" @click="setRate(row)">设比例</el-button>
          <el-button size="small" type="primary" @click="genCoPayDoc(row)">生成佣金单</el-button>
        </template></el-table-column>
      </el-table>
      <div style="margin:8px 0;color:#909399;font-size:13px">佣金支付单（GET /co-pay-docs · PENDING_PAY → 确认支付 → SETTLED）</div>
      <el-table :data="coDocs" border size="small">
        <el-table-column prop="collectorName" label="催收员" /><el-table-column prop="count" label="笔数" width="70" />
        <el-table-column label="金额"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{row}"><el-tag size="small" :type="row.status==='SETTLED'?'success':'warning'">{{ row.status==='SETTLED'?'已结':'待支付' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="110"><template #default="{row}"><el-button v-if="row.status==='PENDING_PAY'" size="small" type="primary" @click="confirmPay(row)">确认支付</el-button></template></el-table-column>
      </el-table>
    </template>
    <template v-if="mySettle">
      <el-divider content-position="left">我的佣金（GET /me/settlement · 催收员自查）</el-divider>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="应结">{{ yuan(mySettle.totalCents ?? mySettle.dueCents) }}</el-descriptions-item>
        <el-descriptions-item label="已结">{{ yuan(mySettle.settledCents) }}</el-descriptions-item>
        <el-descriptions-item label="未结">{{ yuan(mySettle.unsettledCents) }}</el-descriptions-item>
      </el-descriptions>
    </template>

    <!-- 生成支付申请单 -->
    <el-dialog v-model="gdlg" :title="`勾选明细生成支付申请单（${side==='IN'?'收佣':'付佣'}线 POST /payment-requests）`" width="720px">
      <el-form :inline="true"><el-form-item label="批次 id"><el-input v-model="gBatchId" style="width:140px" placeholder="如 1" /></el-form-item><el-button @click="loadLines">加载未结回款明细</el-button></el-form>
      <el-table :data="gLines" border size="small" @selection-change="(v:any)=>gSel=v" max-height="320">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
        <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column prop="channel" label="渠道" /><el-table-column prop="paidAt" label="日期" />
      </el-table>
      <div style="margin-top:6px;color:#606266">已选 {{ gSel.length }} 笔，合计 {{ yuan(gSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
      <template #footer><el-button @click="gdlg=false">取消</el-button><el-button type="primary" :disabled="!gSel.length" @click="submitGenerate">生成</el-button></template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="ddlg" title="支付申请单详情（GET /payment-requests/{id}）" width="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.code }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="基数">{{ yuan(detail.baseCents) }}</el-descriptions-item>
          <el-descriptions-item label="比例">{{ pct(detail.commRate) }}</el-descriptions-item>
          <el-descriptions-item label="应结佣金">{{ yuan(detail.commCents) }}</el-descriptions-item>
          <el-descriptions-item label="凭证">{{ detail.voucher?.fileUrl ?? '—' }}</el-descriptions-item>
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
    <el-dialog v-model="cdlg" :title="(side==='IN'?'确认收款':'支付完成')+'（必带凭证 BR-M9-12d）'" width="440px">
      <el-form label-width="100px">
        <el-form-item label="凭证类型"><el-tag>{{ cform.type==='RECEIPT'?'收款凭证':'支付凭证' }}</el-tag></el-form-item>
        <el-form-item label="凭证文件 URL"><el-input v-model="cform.fileUrl" /></el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="cform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitComplete">完成</el-button></template>
    </el-dialog>
  </el-card>
</template>
