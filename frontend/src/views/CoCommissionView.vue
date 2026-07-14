<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'
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

// 佣金支付单按结算状态分档（服务商一眼看清哪些付了、哪些没付）：
//   待支付=PENDING_PAY（组了单还没确认支付）、已结算=SETTLED（确认支付=锁定）。
const payTab = ref<'all' | 'unpaid' | 'settled'>('all')
const isSettled = (d: any) => d.status === 'SETTLED'
const shownDocs = computed(() => coDocs.value.filter((d) =>
  payTab.value === 'all' ? true : payTab.value === 'settled' ? isSettled(d) : !isSettled(d)))
// 分页：佣金支付单此前硬编码 page:1 size:50，第 51 张单既看不见也无从得知。
const docPage = ref(1)
const docSize = ref(50)
const docTotal = ref(0)
function onDocPage(p: number) { docPage.value = p; load() }

// **口径诚实化**：下面这几个合计是在**当前这一页**上 reduce 出来的。
// 单数超过一页时，它们就不是全量合计 —— 而钱的数字看不出错，最危险。
// 契约里没有「按状态聚合佣金单金额」的端点（缺口已记在 PR 里），
// 在补上它之前，界面必须如实说这是「本页合计」，绝不能让人当成全量。
const docTruncated = computed(() => docTotal.value > coDocs.value.length)
const unpaidCount = computed(() => coDocs.value.filter((d) => !isSettled(d)).length)
const settledCount = computed(() => coDocs.value.filter(isSettled).length)
const unpaidAmount = computed(() => coDocs.value.filter((d) => !isSettled(d)).reduce((s, d) => s + (d.amountCents || 0), 0))
const settledAmount = computed(() => coDocs.value.filter(isSettled).reduce((s, d) => s + (d.amountCents || 0), 0))
// 支付单**按催收员归拢**：同一催收员的多张单聚在一组,组头给单数与金额小计,组内每张单可下钻明细。
const docGroups = computed(() => {
  const m = new Map<string, any>()
  for (const d of shownDocs.value) {
    const key = String(d.collectorId ?? d.collectorName ?? '—')
    const g = m.get(key) ?? { collectorId: key, name: d.collectorName ?? d.collectorId, docs: [] as any[], amount: 0 }
    g.docs.push(d); g.amount += d.amountCents || 0
    m.set(key, g)
  }
  return [...m.values()]
})

async function load() {
  loading.value = true
  const r1 = await api.GET('/co-commissions', { params: { query: { page: 1, size: 50 } } })
  const r2 = await api.GET('/co-pay-docs', { params: { query: { page: docPage.value, size: docSize.value } } })
  docTotal.value = (r2.data as any)?.meta?.total ?? 0
  loading.value = false
  if (r1.error) { ElMessage.error('加载佣金名册失败（可能无权限 403）'); coco.value = [] }
  else coco.value = (r1.data as any)?.items ?? []
  if (r2.error) { ElMessage.error('加载佣金单失败'); coDocs.value = [] }
  else coDocs.value = (r2.data as any)?.items ?? []
}

// ── 佣金明细弹窗：某催收员 → 批次统计(案件数/回款/应结/已结/未结) → 待结算/已结算分档 ──
// 待结算档里勾选某批次的未结明细 → 生成佣金支付单(POST /co-pay-docs)。
const gdlg = ref(false)
const gCollector = ref<any>(null)
const gBatches = ref<any[]>([])        // 该催收员全部批次(带 caseCount/baseCents/dueCents/unsettled…)
const gBatchTab = ref<'unpaid' | 'settled'>('unpaid')
const gBatchId = ref('')               // 当前展开选明细的批次
const gLines = ref<any[]>([])
const gSel = ref<any[]>([])
const gLoading = ref(false)
// 已结=应结-未结;批次待结算 ⟺ 还有未结明细(unsettledLineCount>0)
const settledOf = (b: any) => (b.dueCents ?? 0) - (b.unsettledCents ?? 0)
const unpaidBatches = computed(() => gBatches.value.filter((b) => (b.unsettledLineCount ?? 0) > 0))
const settledBatches = computed(() => gBatches.value.filter((b) => (b.unsettledLineCount ?? 0) === 0 && (b.dueCents ?? 0) > 0))
const shownBatches = computed(() => gBatchTab.value === 'unpaid' ? unpaidBatches.value : settledBatches.value)

async function openDetail2(c: any) {
  gCollector.value = c; gBatchTab.value = 'unpaid'; gBatchId.value = ''; gLines.value = []; gSel.value = []; gBatches.value = []
  gdlg.value = true
  gLoading.value = true
  const { data, error } = await api.GET('/co-commissions/{collectorId}/batches', { params: { path: { collectorId: String(c.collectorId) } } })
  gLoading.value = false
  if (error) { ElMessage.error('加载批次失败'); return }
  gBatches.value = (data as any) ?? []
}
async function pickBatch(b: any) {
  gBatchId.value = b.batchId; gSel.value = []
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
  ElMessage.success(`已生成佣金支付单（${gSel.value.length} 笔明细 · PENDING_PAY）`)
  // 明细弹窗留在原地、刷新该催收员批次统计（未结↓），同时刷新底部佣金单列表
  const c = gCollector.value
  gBatchId.value = ''; gLines.value = []; gSel.value = []
  await openDetail2Refresh(c)
  load()
}
async function openDetail2Refresh(c: any) {
  const { data } = await api.GET('/co-commissions/{collectorId}/batches', { params: { path: { collectorId: String(c.collectorId) } } })
  gBatches.value = (data as any) ?? []
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
          <td><button class="btn txt" @click="openDetail2(row)">明细</button></td>
        </tr>
        <tr v-if="!loading && !coco.length"><td colspan="6" class="note" style="text-align:center">暂无催收员佣金</td></tr>
      </tbody>
    </table>

    <div class="sec-title">佣金支付单（GET /co-pay-docs · PENDING_PAY → 确认支付 → SETTLED）</div>
    <!-- 已结算 / 未结算 分档：服务商对催收员的支付情况一目了然（对齐平台↔服务商对账体验） -->
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap">
      <span class="segctrl">
        <span :class="{ on: payTab === 'all' }" @click="payTab = 'all'">全部 {{ coDocs.length }}</span>
        <span :class="{ on: payTab === 'unpaid' }" @click="payTab = 'unpaid'">未结算 {{ unpaidCount }}</span>
        <span :class="{ on: payTab === 'settled' }" @click="payTab = 'settled'">已结算 {{ settledCount }}</span>
      </span>
      <span class="note" style="margin:0">
        {{ docTruncated ? '本页' : '' }}未结算合计 <b style="color:var(--warn,#e6a23c)">{{ yuan(unpaidAmount) }}</b> ·
        {{ docTruncated ? '本页' : '' }}已结算合计 <b style="color:var(--ok,#67c23a)">{{ yuan(settledAmount) }}</b>
        <span v-if="docTruncated" style="color:var(--warn,#e6a23c)">
          （共 {{ docTotal }} 张单，以上仅为当前页合计——全量聚合端点待补）
        </span>
      </span>
    </div>
    <!-- 按催收员归拢：组头=该催收员的单数/金额小计;组内每张单可下钻明细(单支付单视角) -->
    <table>
      <thead>
        <tr>
          <th>单号 / 催收员</th><th style="width:70px">笔数</th><th>金额</th><th style="width:120px">状态</th><th style="width:140px">创建时间</th><th style="width:200px">操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="g in docGroups" :key="g.collectorId">
          <tr style="background:#f6f8fc">
            <td><b>{{ g.name }}</b></td>
            <td class="num note">{{ g.docs.length }} 张单</td>
            <td class="num"><b>{{ yuan(g.amount) }}</b></td>
            <td colspan="3" class="note">按催收员小计</td>
          </tr>
          <tr v-for="row in g.docs" :key="row.id">
            <td style="padding-left:22px">{{ row.code ?? ('单 #' + row.id) }}</td>
            <td class="num">{{ row.count }}</td>
            <td class="num">{{ yuan(row.amountCents) }}</td>
            <td><span class="tag" :class="row.status==='SETTLED'?'suc':'war'">{{ row.status==='SETTLED'?'已结算':'未结算' }}</span></td>
            <td class="note">{{ row.createdAt ? String(row.createdAt).slice(0,16).replace('T',' ') : '—' }}</td>
            <td>
              <button class="btn txt" @click="openDetail(row)">详情</button>
              <button v-if="row.status==='PENDING_PAY'" class="btn txt" @click="confirmPay(row)">确认支付</button>
            </td>
          </tr>
        </template>
        <tr v-if="!shownDocs.length"><td colspan="6" class="note" style="text-align:center">{{ coDocs.length ? '该分档下暂无佣金单' : '暂无佣金支付单' }}</td></tr>
      </tbody>
    </table>

    <Pager :page="docPage" :size="docSize" :total="docTotal" @update:page="onDocPage" />

    <!-- 佣金明细：某催收员 → 批次统计(案件数/回款/应结/已结/未结) → 待结算/已结算分档；待结算内选明细生成 -->
    <DsDrawer v-model="gdlg" :title="`佣金明细 · ${gCollector?.name ?? ''}`" :width="860">
      <div class="segctrl" style="margin-bottom:10px">
        <span :class="{ on: gBatchTab === 'unpaid' }" @click="gBatchTab = 'unpaid'; gBatchId = ''">待结算 {{ unpaidBatches.length }}</span>
        <span :class="{ on: gBatchTab === 'settled' }" @click="gBatchTab = 'settled'; gBatchId = ''">已结算 {{ settledBatches.length }}</span>
      </div>
      <el-table v-loading="gLoading" :data="shownBatches" border size="small" max-height="300">
        <el-table-column label="批次"><template #default="{row}">{{ row.batchName ?? row.batchId }}</template></el-table-column>
        <el-table-column label="案件数" width="80" align="right"><template #default="{row}">{{ row.caseCount ?? 0 }}</template></el-table-column>
        <el-table-column label="回款金额" align="right"><template #default="{row}">{{ yuan(row.baseCents) }}</template></el-table-column>
        <el-table-column label="比例" width="72" align="right"><template #default="{row}">{{ pct(row.rate) }}</template></el-table-column>
        <el-table-column label="应结" align="right"><template #default="{row}">{{ yuan(row.dueCents) }}</template></el-table-column>
        <el-table-column label="已结" align="right"><template #default="{row}"><span style="color:#67c23a">{{ yuan(settledOf(row)) }}</span></template></el-table-column>
        <el-table-column label="未结" align="right"><template #default="{row}"><span :style="{color: row.unsettledCents ? '#e6a23c' : '#909399'}">{{ yuan(row.unsettledCents) }}</span></template></el-table-column>
        <el-table-column v-if="gBatchTab==='unpaid'" label="操作" width="96">
          <template #default="{row}"><el-button link type="primary" size="small" @click="pickBatch(row)">选明细</el-button></template>
        </el-table-column>
      </el-table>
      <div v-if="gBatchTab==='unpaid' && !unpaidBatches.length" class="note" style="text-align:center;padding:16px 0">该催收员无待结算批次</div>
      <div v-if="gBatchTab==='settled' && !settledBatches.length" class="note" style="text-align:center;padding:16px 0">该催收员暂无已结算批次</div>

      <!-- 待结算：点“选明细”后展开该批次未结明细，勾选生成佣金单 -->
      <template v-if="gBatchTab==='unpaid' && gBatchId">
        <el-divider content-position="left">选择未结明细生成佣金单（批次 {{ gBatchId }}）</el-divider>
        <el-table v-loading="gLoading" :data="gLines" border size="small" @selection-change="(v:any)=>gSel=v" max-height="260">
          <el-table-column type="selection" width="40" />
          <el-table-column prop="ownerName" label="业主" />
          <el-table-column prop="room" label="房号" />
          <el-table-column label="回款"><template #default="{row}">{{ yuan(row.amountCents) }}</template></el-table-column>
          <el-table-column label="渠道"><template #default="{row}"><span :title="row.channel">{{ channelLabel(row.channel) }}</span></template></el-table-column>
          <el-table-column prop="paidAt" label="日期" />
        </el-table>
        <div style="margin-top:6px;color:#606266">已选 {{ gSel.length }} 笔，合计回款 {{ yuan(gSel.reduce((s,l)=>s+(l.amountCents||0),0)) }}</div>
        <el-button type="primary" size="small" style="margin-top:8px" :disabled="!gSel.length" @click="submitGenerate">生成佣金支付单</el-button>
      </template>

      <template #footer><el-button @click="gdlg=false">关闭</el-button></template>
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
