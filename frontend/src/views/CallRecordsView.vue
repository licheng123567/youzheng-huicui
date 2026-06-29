<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

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
// 点开通话 → 复用 CallRecordView 详情/AI 复盘（BR-M5-04a）。无 caseId 不可跳。
function openDetail(row: any) {
  if (!row?.caseId) { ElMessage.warning('该记录无关联案件'); return }
  router.push(`/cases/${row.caseId}/call/${row.id}`)
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>通话记录</div>
      <div class="ops"><span class="note" style="margin:0">GET /recordings · 共 {{ total }} · 点行进 AI 复盘/详情</span></div>
    </div>

    <!-- 筛选栏：对齐后端 8 参数（结案后号码由后端脱敏为 ***，前端直显） -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>电话</span>
        <input class="inp" v-model="filters.phone" placeholder="号码" style="min-width:140px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>房号</span>
        <input class="inp" v-model="filters.room" placeholder="房号" style="min-width:120px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>案件</span>
        <input class="inp" v-model="filters.caseId" placeholder="案件 ID" style="min-width:120px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>项目</span>
        <input class="inp" v-model="filters.projectId" placeholder="项目 ID" style="min-width:120px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>批次</span>
        <input class="inp" v-model="filters.batchId" placeholder="批次 ID" style="min-width:120px" @keyup.enter="search" />
      </div>
      <div class="fi">
        <span>催收员</span>
        <input class="inp" v-model="filters.collectorId" placeholder="催收员 ID" style="min-width:120px" @keyup.enter="search" />
      </div>
      <!-- 日期选择器保留 EL（已全局主题桥接） -->
      <div class="fi">
        <span>起</span>
        <el-date-picker v-model="filters.from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="开始时间" />
      </div>
      <div class="fi">
        <span>止</span>
        <el-date-picker v-model="filters.to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="结束时间" />
      </div>
      <div class="fi">
        <button class="btn" @click="search">查询</button>
        <button class="btn df" @click="reset">重置</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:120px">录音 ID</th>
          <th style="width:120px">案件</th>
          <th style="width:90px">来源</th>
          <th style="width:120px">状态</th>
          <th style="width:180px">录制时间</th>
          <th style="width:90px">时长</th>
          <th style="min-width:120px">号码</th>
          <th style="width:130px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id" class="row-click" @click="openDetail(row)">
          <td>{{ row.id }}</td>
          <td>{{ row.caseId ?? '—' }}</td>
          <td>{{ sourceName(row.source) }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ row.status }}</span></td>
          <td>{{ row.recordedAt ?? '—' }}</td>
          <td class="num">{{ fmtDur(row.durationSec) }}</td>
          <td>{{ row.phone ?? '—' }}</td>
          <td><button class="btn txt" @click.stop="openDetail(row)">AI 复盘/详情</button></td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="8" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
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
</template>
