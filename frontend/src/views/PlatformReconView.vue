<script setup lang="ts">
// 平台「结算对账」双线总账（v1.16.0·仅 SA/SE，经 SettlementEntry 分叉进入）。
// 批次维一行双线：应收/已收/未收（IN 收佣 平台↔物业）+ 应付/已付/未付（OUT 付佣 平台↔服务商）+ 毛利；
// 「明细」下钻案件级双线收付佣状态；未收/未付 可点 → 预锁线别组单（POST /payment-requests 复用）；
// 「单据」跳支付申请单 Tab（发送/完成凭证/撤销 从 SettlementView 平移，仅平台可操作）。
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { payReqStatusLabel, channelLabel } from '../constants/enums'
import { yuan, pct } from '../utils/money'
import DsDrawer from '../components/DsDrawer.vue'

const auth = useAuth()
const canGenerate = computed(() => auth.has('payreq.create'))
const canComplete = computed(() => auth.has('payreq.complete'))

const tab = ref<'ledger' | 'prs'>('ledger')
const period = ref('')            // yyyy-MM，空=全期
const loading = ref(false)
const rows = ref<any[]>([])

async function loadLedger() {
  loading.value = true
  const q: any = { page: 1, size: 50 }
  if (period.value) q.period = period.value
  const { data, error } = await api.GET('/recon/rollup-dual', { params: { query: q } as any })
  loading.value = false
  if (error) { ElMessage.error('加载双线总账失败'); rows.value = []; return }
  rows.value = (data as any)?.items ?? []
}

// ── 支付申请单 Tab（IN/OUT 段控 + 状态/批次过滤）──
const prSide = ref<'IN' | 'OUT'>('IN')
const prStatus = ref('')          // ''=全部
const prBatchId = ref('')         // 批次行「单据」带入
const prs = ref<any[]>([])
const prLoading = ref(false)
async function loadPrs() {
  prLoading.value = true
  const q: any = { side: prSide.value, page: 1, size: 50 }
  if (prStatus.value) q.status = prStatus.value
  if (prBatchId.value) q.batchId = prBatchId.value
  const { data, error } = await api.GET('/payment-requests', { params: { query: q } as any })
  prLoading.value = false
  if (error) { ElMessage.error('加载支付申请单失败'); prs.value = []; return }
  prs.value = (data as any)?.items ?? []
}
function gotoBills(row: any, side: 'IN' | 'OUT') {
  prSide.value = side; prBatchId.value = String(row.batchId); prStatus.value = ''
  tab.value = 'prs'; loadPrs()
}

// ── 案件明细抽屉（GET /batches/{id}/repay-lines·双线列）──
const dDlg = ref(false); const dBatch = ref<any>(null); const dLines = ref<any[]>([]); const dLoading = ref(false)
async function openDetailDrawer(row: any) {
  dBatch.value = row; dLines.value = []; dDlg.value = true; dLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: String(row.batchId) }, query: { page: 1, size: 200 } } as any })
  dLoading.value = false
  if (error) { ElMessage.error('加载回款明细失败'); return }
  dLines.value = (data as any)?.items ?? []
}

// ── 组单抽屉（未收→IN / 未付→OUT 预锁线别；OUT 按服务商分组、一单只付一家）──
const gDlg = ref(false); const gSide = ref<'IN' | 'OUT'>('IN'); const gBatch = ref<any>(null)
const gLines = ref<any[]>([]); const gSel = ref<any[]>([]); const gLoading = ref(false)
async function openGenerate(row: any, side: 'IN' | 'OUT') {
  gSide.value = side; gBatch.value = row; gLines.value = []; gSel.value = []; gDlg.value = true; gLoading.value = true
  const { data, error } = await api.GET('/batches/{id}/repay-lines', { params: { path: { id: String(row.batchId) }, query: { page: 1, size: 100 } } as any })
  gLoading.value = false
  if (error) { ElMessage.error('加载回款明细失败'); return }
  // 本线未占用才可组单（另一线占用不挡——V929 双线独立）
  gLines.value = ((data as any)?.items ?? []).filter((l: any) =>
    side === 'IN' ? (!l.settledIn && !l.paymentRequestIdIn) : (!l.settledOut && !l.paymentRequestIdOut))
  if (!gLines.value.length) ElMessage.info(`该批次无可组${side === 'IN' ? '收佣' : '付佣'}单的明细`)
}
// OUT 一单一家：勾选跨服务商时禁提交并红警（后端仍兜底 403）
const gMixedProviders = computed(() => {
  if (gSide.value !== 'OUT' || gSel.value.length < 2) return false
  const ids = new Set(gSel.value.map((l: any) => l.providerIdAtRepay ?? ''))
  return ids.size > 1
})
async function submitGenerate() {
  if (!gSel.value.length) { ElMessage.warning('请勾选明细'); return }
  if (gMixedProviders.value) { ElMessage.error('付佣单一单只付一家服务商，请只勾选同一服务商的明细'); return }
  const { error } = await api.POST('/payment-requests', { body: { side: gSide.value, batchId: String(gBatch.value.batchId), lineIds: gSel.value.map((l) => String(l.id)) } as any })
  if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已生成${gSide.value === 'IN' ? '收佣' : '付佣'}支付申请单（${gSel.value.length} 笔明细）`)
  gDlg.value = false; loadLedger(); if (tab.value === 'prs') loadPrs()
}

// ── 单据详情 / 发送 / 完成(凭证) / 撤销（从 SettlementView 平移·仅平台）──
const prDlg = ref(false); const prDetail = ref<any>(null)
async function openPrDetail(idLike: any) {
  const id = typeof idLike === 'object' ? idLike.id : String(idLike)
  const { data, error } = await api.GET('/payment-requests/{id}', { params: { path: { id } } })
  if (error) { ElMessage.error('详情加载失败'); return }
  prDetail.value = data; prDlg.value = true
}
async function send(row: any) {
  const { error } = await api.POST('/payment-requests/{id}/send', { params: { path: { id: row.id } } } as any)
  if (error) { ElMessage.error('发送失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已发送'); loadPrs()
}
const cDlg = ref(false); const cform = ref<any>({ fileUrl: '' })
function openComplete(row: any) {
  cform.value = { id: row.id, version: row.version, type: row.side === 'IN' ? 'RECEIPT' : 'PAYMENT', fileUrl: '', side: row.side }
  cDlg.value = true
}
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
  ElMessage.success('已完成（凭证已留存）'); cDlg.value = false; loadPrs(); loadLedger()
}
async function revoke(row: any) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写撤销原因', '撤销支付申请单', {
      inputPlaceholder: '撤销原因（必填）',
      inputValidator: (v: string) => (!!v && v.trim().length > 0) || '撤销原因不能为空',
    })
    reason = String(value).trim()
  } catch { return /* 取消 */ }
  const { error } = await api.POST('/payment-requests/{id}/revoke', { params: { path: { id: row.id } }, body: { version: row.version, reason } as any })
  if (error) { ElMessage.error('撤销失败：' + ((error as any)?.message ?? '已PAID不可撤')); return }
  ElMessage.success('已撤销，本线明细释放'); loadPrs(); loadLedger()
}

// 明细行本线状态标签：已结→suc；在单→点开单据；未组单→war
function lineTag(settled?: boolean, prNo?: string | null) {
  if (settled) return { cls: 'suc', text: '已结' }
  if (prNo) return { cls: 'inf', text: prNo }
  return { cls: 'war', text: '未组单' }
}

onMounted(() => { loadLedger(); loadPrs() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>结算对账 · 平台双线总账</div>
      <div class="ops"><span class="note" style="margin:0">IN 收佣 平台↔物业 / OUT 付佣 平台↔服务商 · 双线独立组单独立结清</span></div>
    </div>

    <div class="toolbar">
      <span class="segctrl">
        <span :class="{ on: tab === 'ledger' }" @click="tab = 'ledger'">批次总账</span>
        <span :class="{ on: tab === 'prs' }" @click="tab = 'prs'; loadPrs()">支付申请单</span>
      </span>
      <template v-if="tab === 'ledger'">
        <el-date-picker v-model="period" type="month" value-format="YYYY-MM" placeholder="全部月份" style="width:140px;margin-left:8px" clearable @change="loadLedger" />
        <button class="btn sm" style="margin-left:8px" @click="loadLedger">刷新</button>
      </template>
    </div>

    <!-- Tab1 批次总账（GET /recon/rollup-dual）-->
    <template v-if="tab === 'ledger'">
      <table v-loading="loading">
        <thead>
          <tr>
            <th>批次</th><th>项目</th><th>回款基数</th><th>回款率</th>
            <th>收佣%</th><th>应收</th><th>已收</th><th>未收</th>
            <th>付佣%</th><th>应付</th><th>已付</th><th>未付</th>
            <th>毛利</th><th style="width:130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.batchId">
            <td><b>{{ row.batch }}</b></td>
            <td>{{ row.proj }}</td>
            <td class="num">{{ yuan(row.baseCents) }}<span class="note" style="margin-left:4px">{{ row.cnt }}笔</span></td>
            <td class="num">{{ pct(row.repayRate) }}</td>
            <td class="num">{{ pct(row.commInRate) }}</td>
            <td class="num">{{ yuan(row.dueInCents) }}</td>
            <td class="num"><span class="tag suc">{{ yuan(row.settledInCents) }}</span></td>
            <td class="num">
              <!-- 未收>0 可点 → 预锁 IN 线组单 -->
              <button v-if="canGenerate && row.unsettledInCents > 0" class="btn txt" style="padding:0" @click="openGenerate(row, 'IN')">
                <span class="tag war" title="点击生成收佣支付申请单">{{ yuan(row.unsettledInCents) }}</span>
              </button>
              <span v-else class="tag inf">{{ yuan(row.unsettledInCents) }}</span>
            </td>
            <template v-if="row.payOutRate != null">
              <td class="num">{{ pct(row.payOutRate) }}</td>
              <td class="num">{{ yuan(row.dueOutCents) }}</td>
              <td class="num"><span class="tag suc">{{ yuan(row.settledOutCents) }}</span></td>
              <td class="num">
                <!-- 未付>0 可点 → 预锁 OUT 线组单（一单一家 按服务商分组勾选）-->
                <button v-if="canGenerate && row.unsettledOutCents > 0" class="btn txt" style="padding:0" @click="openGenerate(row, 'OUT')">
                  <span class="tag war" title="点击生成付佣支付单（对服务商）">{{ yuan(row.unsettledOutCents) }}</span>
                </button>
                <span v-else class="tag inf">{{ yuan(row.unsettledOutCents) }}</span>
              </td>
              <td class="num"><b>{{ yuan(row.grossCents) }}</b></td>
            </template>
            <template v-else>
              <td colspan="4" class="note" style="text-align:center">未设付佣比例</td>
              <td class="num">—</td>
            </template>
            <td>
              <button class="btn txt" @click="openDetailDrawer(row)">明细</button>
              <button class="btn txt" @click="gotoBills(row, 'IN')">单据</button>
            </td>
          </tr>
          <tr v-if="!loading && !rows.length"><td colspan="14" class="note" style="text-align:center">暂无对账数据</td></tr>
        </tbody>
      </table>
      <div class="note" style="margin-top:8px">
        口径：应收/应付 = Σ round(回款×比例)（逐笔）；已收/已付 = 对应线支付申请单 PAID 后锁定的明细；毛利 = 应收 − 应付。
        同一笔回款既计收佣基数也计付佣基数，两线独立组单、独立结清。
      </div>
    </template>

    <!-- Tab2 支付申请单（GET /payment-requests?side&status&batchId）-->
    <template v-else>
      <div class="toolbar">
        <span class="segctrl">
          <span :class="{ on: prSide === 'IN' }" @click="prSide = 'IN'; loadPrs()">收佣单(IN·向物业)</span>
          <span :class="{ on: prSide === 'OUT' }" @click="prSide = 'OUT'; loadPrs()">付佣单(OUT·对服务商)</span>
        </span>
        <el-select v-model="prStatus" placeholder="全部状态" clearable style="width:130px;margin-left:8px" @change="loadPrs">
          <el-option label="待处理" value="PENDING" /><el-option label="已完成" value="PAID" /><el-option label="已撤销" value="VOIDED" />
        </el-select>
        <el-input v-model="prBatchId" placeholder="批次 id 过滤" clearable style="width:140px;margin-left:8px" @change="loadPrs" @clear="loadPrs" />
      </div>
      <table v-loading="prLoading">
        <thead>
          <tr><th>单号</th><th style="width:90px">状态</th><th>批次</th><th>基数</th><th>比例</th><th>应结佣金</th><th style="width:280px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in prs" :key="row.id">
            <td>{{ row.code }}</td>
            <td><span class="tag" :class="row.status==='PAID'?'suc':row.status==='VOIDED'?'inf':'war'" :title="row.status">{{ payReqStatusLabel(row.status) }}</span></td>
            <td>{{ row.batchId }}</td>
            <td class="num">{{ yuan(row.baseCents) }}</td>
            <td class="num">{{ pct(row.commRate) }}</td>
            <td class="num">{{ yuan(row.commCents) }}</td>
            <td>
              <button class="btn txt" @click="openPrDetail(row)">详情</button>
              <template v-if="row.status==='PENDING'">
                <button v-if="canGenerate" class="btn txt" @click="send(row)">发送</button>
                <button v-if="canComplete" class="btn txt" @click="openComplete(row)">{{ prSide==='IN'?'确认收款':'支付完成' }}</button>
                <button v-if="canGenerate" class="btn txt" @click="revoke(row)">撤销</button>
              </template>
            </td>
          </tr>
          <tr v-if="!prLoading && !prs.length"><td colspan="7" class="note" style="text-align:center">暂无支付申请单</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 案件明细抽屉：每笔回款的双线收付佣状态 -->
    <DsDrawer v-model="dDlg" :title="`${dBatch?.batch ?? ''} · 案件收付佣明细`" :width="920">
      <el-table v-loading="dLoading" :data="dLines" border size="small" max-height="560">
        <el-table-column prop="paidAt" label="缴款日期" width="100" />
        <el-table-column prop="ownerName" label="业主" width="90" />
        <el-table-column prop="room" label="房号" width="80" />
        <el-table-column label="回款" width="100"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column label="渠道" width="80"><template #default="{row}"><span :title="row.channel">{{ channelLabel(row.channel) }}</span></template></el-table-column>
        <el-table-column label="收佣(IN)" width="100"><template #default="{row}">{{ yuan(row.commInCents) }}</template></el-table-column>
        <el-table-column label="收佣状态" width="130">
          <template #default="{row}">
            <button v-if="!row.settledIn && row.prNoIn" class="btn txt" style="padding:0" @click="openPrDetail(row.paymentRequestIdIn)">
              <span class="tag inf" title="点击查看单据">{{ row.prNoIn }}</span>
            </button>
            <span v-else class="tag" :class="lineTag(row.settledIn, row.prNoIn).cls">{{ row.settledIn ? '已收' : '未组单' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="付佣(OUT)" width="100"><template #default="{row}">{{ yuan(row.commOutCents) }}</template></el-table-column>
        <el-table-column label="付佣状态" width="130">
          <template #default="{row}">
            <button v-if="!row.settledOut && row.prNoOut" class="btn txt" style="padding:0" @click="openPrDetail(row.paymentRequestIdOut)">
              <span class="tag inf" title="点击查看单据">{{ row.prNoOut }}</span>
            </button>
            <span v-else class="tag" :class="lineTag(row.settledOut, row.prNoOut).cls">{{ row.settledOut ? '已付' : '未组单' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="providerName" label="承接服务商" min-width="100" />
      </el-table>
      <div style="margin-top:6px;color:#606266">
        共 {{ dLines.length }} 笔 · 合计回款 {{ yuan(dLines.reduce((s, l) => s + (l.amountCents || 0), 0)) }}
        · 收佣 {{ yuan(dLines.reduce((s, l) => s + (l.commInCents || 0), 0)) }}
        · 付佣 {{ yuan(dLines.reduce((s, l) => s + (l.commOutCents || 0), 0)) }}
      </div>
      <template #footer><el-button @click="dDlg = false">关闭</el-button></template>
    </DsDrawer>

    <!-- 组单抽屉：side 预锁 + 本线未占用明细 + OUT 一单一家 -->
    <DsDrawer v-model="gDlg" :title="`${gBatch?.batch ?? ''} · 生成${gSide === 'IN' ? '收佣支付申请单（向物业）' : '付佣支付单（对服务商）'}`" :width="760">
      <div v-if="gSide === 'OUT'" class="note" style="margin-bottom:8px">付佣单一单只付一家服务商，请勾选同一「承接服务商」的明细（跨家勾选将被拒）。</div>
      <el-table v-loading="gLoading" :data="gLines" border size="small" @selection-change="(v: any) => gSel = v" max-height="420">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" width="90" /><el-table-column prop="room" label="房号" width="80" />
        <el-table-column label="回款" width="110"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column :label="gSide === 'IN' ? '收佣' : '付佣'" width="110"><template #default="{row}">{{ yuan(gSide === 'IN' ? row.commInCents : row.commOutCents) }}</template></el-table-column>
        <el-table-column prop="paidAt" label="日期" width="100" />
        <el-table-column v-if="gSide === 'OUT'" prop="providerName" label="承接服务商" min-width="100" />
      </el-table>
      <div style="margin-top:6px;color:#606266">
        已选 {{ gSel.length }} 笔 · 合计回款 {{ yuan(gSel.reduce((s, l) => s + (l.amountCents || 0), 0)) }}
        · {{ gSide === 'IN' ? '收佣' : '付佣' }} {{ yuan(gSel.reduce((s, l) => s + ((gSide === 'IN' ? l.commInCents : l.commOutCents) || 0), 0)) }}
      </div>
      <div v-if="gMixedProviders" class="alert danger" style="margin-top:8px"><b>跨服务商勾选：</b>付佣单一单只付一家，请调整勾选。</div>
      <template #footer>
        <el-button @click="gDlg = false">取消</el-button>
        <el-button type="primary" :disabled="!gSel.length || gMixedProviders" @click="submitGenerate">生成</el-button>
      </template>
    </DsDrawer>

    <!-- 单据详情（复用 SettlementView 结构）-->
    <el-dialog v-model="prDlg" title="支付申请单详情（GET /payment-requests/{id}）" width="640px">
      <template v-if="prDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ prDetail.code }}</el-descriptions-item>
          <el-descriptions-item label="状态"><span :title="prDetail.status">{{ payReqStatusLabel(prDetail.status) }}</span></el-descriptions-item>
          <el-descriptions-item label="线别">{{ prDetail.side === 'IN' ? '收佣(IN·向物业)' : '付佣(OUT·对服务商)' }}</el-descriptions-item>
          <el-descriptions-item label="比例">{{ pct(prDetail.commRate) }}</el-descriptions-item>
          <el-descriptions-item label="基数">{{ yuan(prDetail.baseCents) }}</el-descriptions-item>
          <el-descriptions-item label="应结佣金">{{ yuan(prDetail.commCents) }}</el-descriptions-item>
          <el-descriptions-item label="凭证">{{ prDetail.voucher?.fileUrl ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="电子签章">
            <el-tag size="small" :type="prDetail.sealed ? 'success' : 'info'">{{ prDetail.sealed ? '已签章' : '未签章' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>明细快照（lines）</el-divider>
        <el-table :data="prDetail.lines ?? []" border size="small" max-height="240">
          <el-table-column prop="ownerName" label="业主" /><el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents ?? row.amountCents) }}</template></el-table-column>
          <el-table-column label="佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- 完成（凭证·平台落地支付动作）-->
    <DsDrawer v-model="cDlg" :title="cform.side === 'IN' ? '确认收款' : '支付完成'" :width="440">
      <el-form label-width="100px">
        <el-form-item label="凭证类型"><el-tag>{{ cform.type === 'RECEIPT' ? '收款凭证' : '支付凭证' }}</el-tag></el-form-item>
        <el-form-item label="上传凭证" required>
          <el-upload action="/api/uploads" :show-file-list="false" :on-success="onVoucherUpload" :on-error="onVoucherUploadError">
            <el-button size="small">选择文件上传</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="凭证文件 URL" required>
          <el-input v-model="cform.fileUrl" placeholder="上传后自动回填，或手填可访问地址（必填）" />
        </el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="cform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cDlg = false">取消</el-button><el-button type="primary" @click="submitComplete">完成</el-button></template>
    </DsDrawer>
  </div>
</template>
