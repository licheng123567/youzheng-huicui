<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { callRecStatusLabel } from '../constants/enums'
import AiReviewPanel from '../components/AiReviewPanel.vue'

// US-M4-12 / BR-M4-22 通话记录查询：GET /recordings 全过滤+分页，点开行进 CallRecordView(AI 复盘/详情)。
// 可见范围由后端 range 裁剪；结案后 phone 由后端脱敏为 '***'，前端直显。
const router = useRouter()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
// 过滤表单（对齐后端 8 参数）。初始即初始化全字段，防数组/对象字段未定义白屏。
const filters = ref<any>({ phone: '', room: '', caseId: '', projectId: '', batchId: '', collectorId: '', from: '', to: '' })

// 仅传非空过滤项，避免空串污染查询。
function buildQuery() {
  const q: any = { page: page.value, size: size.value }
  const f = filters.value
  if (f.phone) q.phone = f.phone
  if (f.room) q.room = f.room
  if (f.caseId) q.caseId = f.caseId
  if (f.projectId) q.projectId = f.projectId
  if (f.batchId) q.batchId = f.batchId
  if (f.collectorId) q.collectorId = f.collectorId
  if (f.from) q.from = f.from
  if (f.to) q.to = f.to
  return q
}

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/recordings', { params: { query: buildQuery() } as any })
  loading.value = false
  if (error) { ElMessage.error('加载通话记录失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

// 纯展示辅助：录音状态 → ds-admin .tag 配色（suc/dan/war/inf）
const STATUS_TAG: Record<string, string> = { READY: 'suc', FAILED: 'dan', QUOTA_BLOCKED: 'war' }
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'
// 纯展示辅助：秒 → mm:ss
const fmtDur = (sec?: number | null) => {
  if (sec == null) return '—'
  const m = Math.floor(sec / 60), s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
// 纯展示辅助：来源中文
const sourceName = (src?: string) => src === 'APP_AUTO' ? '自动' : src === 'MANUAL' ? '手动' : (src ?? '—')

function search() { page.value = 1; load() }
function reset() {
  filters.value = { phone: '', room: '', caseId: '', projectId: '', batchId: '', collectorId: '', from: '', to: '' }
  page.value = 1
  load()
}
// AI 复盘：统一走右侧复盘面板（AiReviewPanel·对标原型 .reviewpanel），不再整页跳转（BR-M5-04a）。
const reviewOpen = ref(false)
const reviewRow = ref<any>(null)
function openDetail(row: any) {
  if (row?.status !== 'READY') { ElMessage.info('录音尚未解析完成（' + callRecStatusLabel(row?.status) + '），暂无复盘'); return }
  reviewRow.value = row
  reviewOpen.value = true
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>通话记录</div>
      <div class="ops"><span class="note" style="margin:0">GET /recordings · 共 {{ total }} · 点行进 AI 复盘/详情</span></div>
    </div>

    <!-- 筛选栏：对齐高保真原型 -->
    <div class="toolbar" style="margin-bottom:10px">
      <input class="inp" v-model="filters.phone" placeholder="搜索 业主 / 房号 / 电话" aria-label="搜索" style="min-width:180px" @keyup.enter="search">
      <input class="inp" v-model="filters.projectId" placeholder="项目 ID" aria-label="项目" style="min-width:120px">
      <input class="inp" v-model="filters.batchId" placeholder="批次 ID" aria-label="批次" style="min-width:120px">
      <el-date-picker v-model="filters.from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="开始时间" />
      <span class="note" style="margin:0">~</span>
      <el-date-picker v-model="filters.to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="结束时间" />
      <button class="btn df sm" @click="reset">重置</button>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:170px">通话时间</th>
          <th style="min-width:80px">业主</th>
          <th style="width:80px">房号</th>
          <th style="min-width:100px">项目</th>
          <th style="min-width:100px">批次</th>
          <th style="min-width:110px">号码</th>
          <th style="width:70px">时长</th>
          <th style="width:80px">结果</th>
          <th style="width:150px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id" class="row-click" @click="openDetail(row)">
          <td class="num">{{ row.recordedAt ? String(row.recordedAt).slice(0, 16).replace('T', ' ') : '—' }}</td>
          <td>{{ row.ownerName ?? '—' }}</td>
          <td>{{ row.room ?? '—' }}</td>
          <td>{{ row.projectName ?? '—' }}</td>
          <td>{{ row.batchCode ?? '—' }}</td>
          <td>{{ row.phone ?? '—' }}</td>
          <td class="num">{{ fmtDur(row.durationSec) }}</td>
          <td><span class="tag" :class="statusTag(row.status)" :title="row.status">{{ callRecStatusLabel(row.status) }}</span></td>
          <td @click.stop>
            <button class="btn txt" @click="openDetail(row)">AI 复盘</button>
            <button v-if="row.caseId" class="btn txt" @click="router.push('/cases/' + row.caseId)">进案件</button>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="9" style="text-align:center;color:var(--ph);padding:40px 0">
            <div style="font-size:36px">📞</div>
            <div class="note" style="margin-top:8px">无匹配的通话记录。</div>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="page > 1 && (page--, load())">‹</div>
      <div class="pg on">{{ page }}</div>
      <div class="pg" @click="page * size < total && (page++, load())">›</div>
    </div>
  </div>

  <!-- AI 复盘面板（统一布局：左对话记录 / 右小结+结果标记+风险+建议） -->
  <AiReviewPanel
    v-model:open="reviewOpen"
    :recording-id="String(reviewRow?.id ?? '')"
    :case-id="reviewRow?.caseId ? String(reviewRow.caseId) : undefined"
    :owner-name="reviewRow?.ownerName"
    :room="reviewRow?.room"
  />
</template>
