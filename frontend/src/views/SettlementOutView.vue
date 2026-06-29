<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 付佣对账（OUT 线·服务商↔平台）独立子页：对账汇总 + 支付申请单。
// OUT 线生成方=服务商；完成(付款+上传支付凭证)恒由平台受理(契约 x-data-scope=platform)。
// 权限/脱敏一律沿用后端返回（跨线/越权 → 403，前端仅提示，不做本地权限假设）。
const SIDE = 'OUT' as const

const rollup = ref<any[]>([])
const prs = ref<any[]>([])
const loading = ref(false)

const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(2) + '%')
const newKey = () => (crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`)

async function load() {
  loading.value = true
  const rk = await api.GET('/recon/rollup', { params: { query: { side: SIDE, page: 1 } } })
  rollup.value = (rk.data as any)?.items ?? []
  const pr = await api.GET('/payment-requests', { params: { query: { side: SIDE, page: 1, size: 20 } } })
  loading.value = false
  if (pr.error) { ElMessage.error('加载支付申请单失败（可能跨线 403）'); prs.value = []; return }
  prs.value = (pr.data as any)?.items ?? []
}

// ── 生成支付申请单：填批次 → 加载未结回款明细 → 勾选 → POST /payment-requests(side=OUT) ──
const gdlg = ref(false)
const gBatchId = ref('')
const gLines = ref<any[]>([])
const gSel = ref<any[]>([])
const gLoading = ref(false)
function openGenerate() { gBatchId.value = ''; gLines.value = []; gSel.value = []; gdlg.value = true }
async function loadLines() {
  if (!gBatchId.value) { ElMessage.warning('请填批次 id'); return }
  gSel.value = []
  gLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: gBatchId.value }, query: { page: 1, size: 100 } } })
  gLoading.value = false
  if (error) { ElMessage.error('加载回款明细失败'); gLines.value = []; return }
  // 仅可组单：未结清且未入单
  gLines.value = ((data as any)?.items ?? []).filter((l: any) => !l.settled && !l.paymentRequestId)
  if (!gLines.value.length) ElMessage.info('该批次无可组单的未结回款明细')
}
async function submitGenerate() {
  if (!gSel.value.length) { ElMessage.warning('请勾选明细'); return }
  const { error } = await api.POST('/payment-requests', {
    params: { header: { 'Idempotency-Key': newKey() } },
    body: { side: SIDE, batchId: gBatchId.value, lineIds: gSel.value.map((l) => String(l.id)) },
  })
  if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '可能错线/防倒挂')); return }
  ElMessage.success(`已生成支付申请单（${gSel.value.length} 笔明细）`); gdlg.value = false; load()
}

// 发送给平台
async function send(row: any) {
  const { error } = await api.POST('/payment-requests/{id}/send', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('发送失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已发送'); load()
}

// 详情
const ddlg = ref(false)
const detail = ref<any>(null)
async function openDetail(row: any) {
  const { data, error } = await api.GET('/payment-requests/{id}', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('详情加载失败'); return }
  detail.value = data; ddlg.value = true
}

// ── 完成付款：必带支付凭证(PAYMENT)，凭证经上传入口取得地址，必填、无 mock 默认 URL ──
const cdlg = ref(false)
const cform = ref<{ id?: string; version?: number; fileUrl: string }>({ fileUrl: '' })
function openComplete(row: any) { cform.value = { id: row.id, version: row.version, fileUrl: '' }; cdlg.value = true }
function onVoucherUpload(resp: any) {
  // 上传成功 → 取后端返回地址回填；不同后端字段兼容
  const url = resp?.url ?? resp?.fileUrl ?? resp?.data?.url ?? ''
  if (!url) { ElMessage.warning('上传成功但未取到文件地址，请手填凭证 URL'); return }
  cform.value.fileUrl = url; ElMessage.success('凭证已上传')
}
function onVoucherUploadError() { ElMessage.error('上传失败，请重试或手填凭证 URL') }
async function submitComplete() {
  if (!cform.value.fileUrl?.trim()) { ElMessage.warning('请先上传或填写支付凭证（必填 BR-M9-12d）'); return }
  const { error } = await api.POST('/payment-requests/{id}/complete', {
    params: { path: { id: cform.value.id! }, header: { 'Idempotency-Key': newKey() } },
    body: { voucher: { type: 'PAYMENT', fileUrl: cform.value.fileUrl.trim() }, version: cform.value.version! },
  })
  if (error) { ElMessage.error('完成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已完成付款（凭证已留存）'); cdlg.value = false; load()
}

// ── 撤回：原因弹框输入必填、不硬编码；version 乐观锁 ──
const rdlg = ref(false)
const rform = ref<{ id?: string; version?: number; reason: string }>({ reason: '' })
function openRevoke(row: any) { rform.value = { id: row.id, version: row.version, reason: '' }; rdlg.value = true }
async function submitRevoke() {
  if (!rform.value.reason?.trim()) { ElMessage.warning('请填写撤回原因（必填）'); return }
  const { error } = await api.POST('/payment-requests/{id}/revoke', {
    params: { path: { id: rform.value.id! } },
    body: { reason: rform.value.reason.trim(), version: rform.value.version! },
  })
  if (error) { ElMessage.error('撤回失败：' + ((error as any)?.message ?? '已PAID不可撤')); return }
  ElMessage.success('已撤回，明细释放'); rdlg.value = false; load()
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>付佣对账 · OUT 线（服务商↔平台）</div>
      <div class="ops">
        <span class="note" style="margin:0">勾选回款明细生成支付申请单 → 发送 → 平台付款(上传支付凭证)</span>
        <button class="btn sm" @click="openGenerate">勾选明细生成支付申请单</button>
      </div>
    </div>

    <div class="sec-title">对账汇总（GET /recon/rollup · OUT 口径）</div>
    <table>
      <thead>
        <tr>
          <th>批次</th><th>项目</th><th>回款基数</th><th>回款率</th><th>比例</th><th>应结</th><th>已结</th><th>未结</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rollup" :key="row.batchId ?? row.batch">
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

    <div class="sec-title">支付申请单（GET /payment-requests?side=OUT）</div>
    <table v-loading="loading">
      <thead>
        <tr>
          <th>单号</th><th style="width:90px">状态</th><th>基数</th><th>比例</th><th>应结佣金</th><th style="width:300px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in prs" :key="row.id">
          <td>{{ row.code }}</td>
          <td><span class="tag" :class="row.status==='PAID'?'suc':row.status==='VOIDED'?'inf':'war'">{{ row.status }}</span></td>
          <td class="num">{{ yuan(row.baseCents) }}</td>
          <td class="num">{{ pct(row.commRate) }}</td>
          <td class="num">{{ yuan(row.commCents) }}</td>
          <td>
            <button class="btn txt" @click="openDetail(row)">详情</button>
            <a v-if="row.documentUrl" class="link" :href="row.documentUrl" target="_blank" style="margin:0 8px;font-size:13px">下载</a>
            <button v-else class="btn txt" disabled>下载</button>
            <template v-if="row.status==='PENDING'">
              <button class="btn txt" @click="send(row)">发送</button>
              <button class="btn txt" @click="openComplete(row)">支付完成</button>
              <button class="btn txt" @click="openRevoke(row)">撤回</button>
            </template>
          </td>
        </tr>
        <tr v-if="!loading && !prs.length"><td colspan="6" class="note" style="text-align:center">暂无支付申请单</td></tr>
      </tbody>
    </table>

    <!-- 生成支付申请单 -->
    <el-dialog v-model="gdlg" title="勾选明细生成支付申请单（OUT 线 POST /payment-requests）" width="720px">
      <el-form :inline="true">
        <el-form-item label="批次 id"><el-input v-model="gBatchId" style="width:200px" placeholder="批次 ID（字符串）" /></el-form-item>
        <el-button @click="loadLines">加载未结回款明细</el-button>
      </el-form>
      <el-table v-loading="gLoading" :data="gLines" border size="small" @selection-change="(v:any)=>gSel=v" max-height="320">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" />
        <el-table-column prop="room" label="房号" />
        <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column prop="channel" label="渠道" />
        <el-table-column prop="paidAt" label="日期" />
      </el-table>
      <div style="margin-top:6px;color:#606266">已选 {{ gSel.length }} 笔，合计回款 {{ yuan(gSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
      <template #footer>
        <el-button @click="gdlg=false">取消</el-button>
        <el-button type="primary" :disabled="!gSel.length" @click="submitGenerate">生成</el-button>
      </template>
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
          <el-table-column prop="ownerName" label="业主" />
          <el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents) }}</template></el-table-column>
          <el-table-column label="佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- 完成付款：支付凭证必填、上传入口、无默认 URL -->
    <el-dialog v-model="cdlg" title="支付完成（必带支付凭证 BR-M9-12d）" width="460px">
      <el-form label-width="100px">
        <el-form-item label="凭证类型"><el-tag>支付凭证</el-tag></el-form-item>
        <el-form-item label="上传凭证" required>
          <el-upload action="/api/uploads" :show-file-list="false" :on-success="onVoucherUpload" :on-error="onVoucherUploadError">
            <el-button>选择文件上传</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="凭证文件 URL" required>
          <el-input v-model="cform.fileUrl" placeholder="上传后自动回填，亦可手填可访问地址（必填）" />
        </el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="cform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cdlg=false">取消</el-button>
        <el-button type="primary" :disabled="!cform.fileUrl?.trim()" @click="submitComplete">完成付款</el-button>
      </template>
    </el-dialog>

    <!-- 撤回：原因必填 -->
    <el-dialog v-model="rdlg" title="撤回支付申请单（仅 PENDING 可撤 BR-M9-12d）" width="440px">
      <el-form label-width="100px">
        <el-form-item label="撤回原因" required>
          <el-input v-model="rform.reason" type="textarea" :rows="3" placeholder="请填写撤回原因（必填）" />
        </el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="rform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rdlg=false">取消</el-button>
        <el-button type="primary" :disabled="!rform.reason?.trim()" @click="submitRevoke">确认撤回</el-button>
      </template>
    </el-dialog>
  </div>
</template>
