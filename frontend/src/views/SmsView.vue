<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 短信（将挂 /sms）：短信发送明细(GET /sms-records) + 导出(GET /sms-records/export)。
// 过滤：项目/案件/状态/时间(from,to)。失败行标红（BR-M9-08：失败不退条数，仅供查看失败原因）。
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

// 筛选字段初始即初始化（防白屏铁律）。range 由 el-date-picker 维护成 [from,to]。
const filters = reactive<{ projectId: string; caseId: string; status: string; from: string; to: string }>({
  projectId: '',
  caseId: '',
  status: '',
  from: '',
  to: '',
})
const range = ref<string[]>([])
const page = ref(1)
const size = ref(20)

// SmsSendStatusEnum: SENT/FAILED/DELIVERED → 中文 + .tag 配色（失败=红 dan）。
const STATUS_OPTS: { value: string; label: string }[] = [
  { value: 'SENT', label: '已发送' },
  { value: 'DELIVERED', label: '已送达' },
  { value: 'FAILED', label: '失败' },
]
const STATUS_TAG: Record<string, string> = { SENT: 'suc', DELIVERED: 'suc', FAILED: 'dan' }
const statusName = (s?: string) => STATUS_OPTS.find((o) => o.value === s)?.label ?? s ?? '—'
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'

// 可复用列表请求：filters + 分页透传 /sms-records（空串不传）。SmsSendRecord: sentAt/template/caseId/projectId/status/failureReason。
async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (filters.projectId) query.projectId = filters.projectId
  if (filters.caseId) query.caseId = filters.caseId
  if (filters.status) query.status = filters.status
  if (filters.from) query.from = filters.from
  if (filters.to) query.to = filters.to
  const { data, error } = await api.GET('/sms-records', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function syncRange() {
  const r = range.value || []
  filters.from = r[0] || ''
  filters.to = r[1] || ''
}

function search() { syncRange(); page.value = 1; load() }
function reset() {
  filters.projectId = ''
  filters.caseId = ''
  filters.status = ''
  filters.from = ''
  filters.to = ''
  range.value = []
  search()
}

function onPage(p: number) { if (p < 1 || p > pageCount.value || p === page.value) return; page.value = p; load() }
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

// 导出（GET /sms-records/export · 同 scope/过滤 · 返回文件 url 占位 TBD）。
async function exportSms() {
  syncRange()
  const query: Record<string, any> = {}
  if (filters.projectId) query.projectId = filters.projectId
  if (filters.caseId) query.caseId = filters.caseId
  if (filters.status) query.status = filters.status
  if (filters.from) query.from = filters.from
  if (filters.to) query.to = filters.to
  const { data, error } = await api.GET('/sms-records/export', { params: { query } as any })
  if (error) { ElMessage.error('导出失败：' + ((error as any)?.message ?? '')); return }
  const url = (data as any)?.url
  if (url) { window.open(url, '_blank'); ElMessage.success('导出已生成') }
  else { ElMessage.info('导出文件生成中（文件通道占位 · TBD）') }
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>短信</div>
      <div class="ops"><span class="note" style="margin:0">GET /sms-records · 共 {{ total }} · 发送明细（成功 / 失败 / 未达）</span></div>
    </div>

    <!-- 筛选栏：项目/案件/状态/时间 -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>项目</span>
        <input class="inp" v-model="filters.projectId" placeholder="项目ID" style="min-width:130px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>案件</span>
        <input class="inp" v-model="filters.caseId" placeholder="案件ID" style="min-width:130px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>状态</span>
        <select class="inp" v-model="filters.status" @change="search">
          <option value="">全部状态</option>
          <option v-for="s in STATUS_OPTS" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
      </div>
      <div class="fi">
        <span>时间</span>
        <el-date-picker
          v-model="range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="至"
          start-placeholder="起"
          end-placeholder="止"
          size="small"
          style="width:340px"
        />
      </div>
      <div class="fi">
        <button class="btn" @click="search">查询</button>
        <button class="btn df" @click="reset">重置</button>
        <button class="btn df" @click="exportSms">导出</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:180px">发送时间</th>
          <th>模板</th>
          <th style="width:120px">案件</th>
          <th style="width:120px">项目</th>
          <th style="width:100px">状态</th>
          <th style="width:200px">失败原因</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in items" :key="row.id || i" :class="{ 'sms-failed-row': row.status === 'FAILED' }">
          <td>{{ row.sentAt || '—' }}</td>
          <td>{{ row.template || '—' }}</td>
          <td>{{ row.caseId || '—' }}</td>
          <td>{{ row.projectId || '—' }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusName(row.status) }}</span></td>
          <td style="color:var(--danger)">{{ row.status === 'FAILED' ? (row.failureReason || '未知') : '' }}</td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="6" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="onPage(page - 1)">‹</div>
      <div v-for="p in pages" :key="p" class="pg" :class="{ on: p === page }" @click="onPage(p)">{{ p }}</div>
      <div class="pg" @click="onPage(page + 1)">›</div>
    </div>
  </div>
</template>

<style scoped>
.sms-failed-row {
  background-color: #fef0f0;
}
</style>
