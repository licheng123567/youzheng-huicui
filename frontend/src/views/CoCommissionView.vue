<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { channelLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// 内催佣金（服务商内部）独立子页：催收员佣金名册 → 某人批次穿透 → 勾选未结明细生成佣金单 → 确认支付。
// 资金双线硬隔离：平台/物业不可见（后端裁剪，前端仅按返回展示，跨线/越权 → 403 仅提示）。
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(2) + '%')
const newKey = () => (crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`)

const coco = ref<any[]>([])   // 佣金名册（按人聚合）
const coDocs = ref<any[]>([]) // 佣金单
const loading = ref(false)

async function load() {
  loading.value = true
  const r1 = await api.GET('/co-commissions', { params: { query: { page: 1, size: 50 } } })
  const r2 = await api.GET('/co-pay-docs', { params: { query: { page: 1, size: 50 } } })
  loading.value = false
  if (r1.error) { ElMessage.error('加载佣金名册失败（可能无权限 403）'); coco.value = [] }
  else coco.value = (r1.data as any)?.items ?? []
  if (r2.error) { ElMessage.error('加载佣金单失败'); coDocs.value = [] }
  else coDocs.value = (r2.data as any)?.items ?? []
}

// ── 生成佣金单：催收员 → 批次(GET /co-commissions/{collectorId}/batches) → 该批未结明细勾选 → POST /co-pay-docs ──
const gdlg = ref(false)
const gCollector = ref<any>(null)
const gBatches = ref<any[]>([])
const gBatchId = ref('')
const gLines = ref<any[]>([])
const gSel = ref<any[]>([])
const gLoading = ref(false)
async function openGenerate(c: any) {
  gCollector.value = c; gBatchId.value = ''; gLines.value = []; gSel.value = []; gBatches.value = []
  gdlg.value = true
  gLoading.value = true
  const { data, error } = await api.GET('/co-commissions/{collectorId}/batches', { params: { path: { collectorId: String(c.collectorId) } } })
  gLoading.value = false
  if (error) { ElMessage.error('加载批次失败'); return }
  gBatches.value = (data as any) ?? []
}
async function loadLines() {
  if (!gBatchId.value) { ElMessage.warning('请先选择批次'); return }
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
  const { error } = await api.POST('/co-pay-docs', {
    params: { header: { 'Idempotency-Key': newKey() } },
    body: { collectorId: String(gCollector.value.collectorId), lineIds: gSel.value.map((l) => String(l.id)) },
  })
  if (error) { ElMessage.error('生成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已生成佣金支付单（${gSel.value.length} 笔明细 · PENDING_PAY）`); gdlg.value = false; load()
}

// 确认支付（无 body·带幂等键）
async function confirmPay(doc: any) {
  const { error } = await api.POST('/co-pay-docs/{id}/confirm-pay', {
    params: { path: { id: doc.id }, header: { 'Idempotency-Key': newKey() } },
  })
  if (error) { ElMessage.error('确认支付失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已确认支付'); load()
}

// 佣金单详情（GET /co-pay-docs/{id}·lines 穿透快照）
const ddlg = ref(false)
const detail = ref<any>(null)
async function openDetail(row: any) {
  const { data, error } = await api.GET('/co-pay-docs/{id}', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('佣金单详情加载失败'); return }
  detail.value = data; ddlg.value = true
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>内催佣金 · 服务商内部</div>
      <div class="ops"><span class="note" style="margin:0">催收员佣金穿透：人 → 批次 → 案件回款；生成佣金单 → 确认支付</span></div>
    </div>

    <div class="sec-title">佣金名册（GET /co-commissions · 按催收员聚合）</div>
    <table v-loading="loading">
      <thead>
        <tr>
          <th>催收员</th><th style="width:80px">批次数</th><th>应结</th><th>已结</th><th>未结</th><th style="width:140px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in coco" :key="row.collectorId">
          <td>{{ row.name }}</td>
          <td class="num">{{ row.batchCount }}</td>
          <td class="num">{{ yuan(row.dueCents) }}</td>
          <td class="num"><span class="tag suc">{{ yuan(row.settledCents) }}</span></td>
          <td class="num"><span class="tag" :class="row.unsettledCents ? 'war' : 'inf'">{{ yuan(row.unsettledCents) }}</span></td>
          <td><button class="btn txt" @click="openGenerate(row)">生成佣金单</button></td>
        </tr>
        <tr v-if="!loading && !coco.length"><td colspan="6" class="note" style="text-align:center">暂无催收员佣金</td></tr>
      </tbody>
    </table>

    <div class="sec-title">佣金支付单（GET /co-pay-docs · PENDING_PAY → 确认支付 → SETTLED）</div>
    <table>
      <thead>
        <tr>
          <th>催收员</th><th style="width:70px">笔数</th><th>金额</th><th style="width:120px">状态</th><th style="width:200px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in coDocs" :key="row.id">
          <td>{{ row.collectorName ?? row.collectorId }}</td>
          <td class="num">{{ row.count }}</td>
          <td class="num">{{ yuan(row.amountCents) }}</td>
          <td><span class="tag" :class="row.status==='SETTLED'?'suc':'war'">{{ row.status==='SETTLED'?'已结':'待支付' }}</span></td>
          <td>
            <button class="btn txt" @click="openDetail(row)">详情</button>
            <button v-if="row.status==='PENDING_PAY'" class="btn txt" @click="confirmPay(row)">确认支付</button>
          </td>
        </tr>
        <tr v-if="!coDocs.length"><td colspan="5" class="note" style="text-align:center">暂无佣金支付单</td></tr>
      </tbody>
    </table>

    <!-- 生成佣金单：人 → 批次 → 未结明细勾选 → POST /co-pay-docs -->
    <DsDrawer v-model="gdlg" :title="`生成佣金单 · ${gCollector?.name ?? ''}`" :width="760">
      <el-form :inline="true">
        <el-form-item label="批次">
          <el-select v-model="gBatchId" style="width:380px" placeholder="选择该催收员的批次" @change="loadLines">
            <el-option v-for="b in gBatches" :key="b.batchId" :value="b.batchId"
              :label="`${b.batchName ?? b.batchId}（比例 ${pct(b.rate)} · 未结 ${yuan(b.unsettledCents)} · ${b.unsettledLineCount ?? 0} 笔）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table v-loading="gLoading" :data="gLines" border size="small" @selection-change="(v:any)=>gSel=v" max-height="320">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="ownerName" label="业主" />
        <el-table-column prop="room" label="房号" />
        <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
        <el-table-column label="渠道"><template #default="{row}"><span :title="row.channel">{{ channelLabel(row.channel) }}</span></template></el-table-column>
        <el-table-column prop="paidAt" label="日期" />
      </el-table>
      <div style="margin-top:6px;color:#606266">已选 {{ gSel.length }} 笔，合计回款 {{ yuan(gSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
      <template #footer>
        <el-button @click="gdlg=false">取消</el-button>
        <el-button type="primary" :disabled="!gSel.length" @click="submitGenerate">生成佣金单</el-button>
      </template>
    </DsDrawer>

    <!-- 佣金单详情 -->
    <DsDrawer v-model="ddlg" title="佣金支付单详情" :width="640">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="催收员">{{ detail.collectorName ?? detail.collectorId }}</el-descriptions-item>
          <el-descriptions-item label="笔数">{{ detail.count ?? (detail.lines?.length ?? 0) }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ yuan(detail.amountCents) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status==='SETTLED'?'已结':'待支付' }}</el-descriptions-item>
          <el-descriptions-item label="单据下载">
            <el-link v-if="detail.documentUrl" type="primary" :href="detail.documentUrl" target="_blank">下载单据</el-link>
            <span v-else style="color:#909399">单据通道 TBD</span>
          </el-descriptions-item>
          <el-descriptions-item label="电子签章">
            <el-tag size="small" :type="detail.sealed ? 'success' : 'info'">{{ detail.sealed ? '已签章' : '未签章' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>明细快照（lines · 催收员→批次→案件回款）</el-divider>
        <el-table :data="detail.lines ?? []" border size="small" max-height="240">
          <el-table-column prop="ownerName" label="业主" />
          <el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.repayCents) }}</template></el-table-column>
          <el-table-column label="佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
        </el-table>
      </template>
    </DsDrawer>
  </div>
</template>
