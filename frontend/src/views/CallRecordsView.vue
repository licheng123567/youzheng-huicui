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
  <el-card :header="`通话记录（GET /recordings · 共 ${total}）`">
    <el-form :inline="true" style="margin-bottom:4px">
      <el-form-item label="电话"><el-input v-model="filters.phone" clearable style="width:140px" placeholder="号码" @keyup.enter="search" /></el-form-item>
      <el-form-item label="房号"><el-input v-model="filters.room" clearable style="width:120px" placeholder="房号" @keyup.enter="search" /></el-form-item>
      <el-form-item label="案件"><el-input v-model="filters.caseId" clearable style="width:120px" placeholder="案件 ID" @keyup.enter="search" /></el-form-item>
      <el-form-item label="项目"><el-input v-model="filters.projectId" clearable style="width:120px" placeholder="项目 ID" @keyup.enter="search" /></el-form-item>
      <el-form-item label="批次"><el-input v-model="filters.batchId" clearable style="width:120px" placeholder="批次 ID" @keyup.enter="search" /></el-form-item>
      <el-form-item label="催收员"><el-input v-model="filters.collectorId" clearable style="width:120px" placeholder="催收员 ID" @keyup.enter="search" /></el-form-item>
      <el-form-item label="起"><el-date-picker v-model="filters.from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="开始时间" /></el-form-item>
      <el-form-item label="止"><el-date-picker v-model="filters.to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" style="width:190px" placeholder="结束时间" /></el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" border size="small" @row-click="openDetail" style="cursor:pointer">
      <el-table-column prop="id" label="录音 ID" width="120" />
      <el-table-column prop="caseId" label="案件" width="120" />
      <el-table-column prop="source" label="来源" width="100">
        <template #default="{ row }">{{ row.source==='APP_AUTO'?'自动':row.source==='MANUAL'?'手动':(row.source ?? '—') }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }"><el-tag size="small" :type="row.status==='READY'?'success':row.status==='FAILED'?'danger':'warning'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="recordedAt" label="录制时间" width="180">
        <template #default="{ row }">{{ row.recordedAt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="时长" width="90">
        <template #default="{ row }">{{ row.durationSec == null ? '—' : row.durationSec + 's' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="号码" min-width="120">
        <template #default="{ row }">{{ row.phone ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }"><el-button size="small" text type="primary" @click.stop="openDetail(row)">AI 复盘/详情</el-button></template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next" :total="total"
      :page-size="size" :current-page="page" @current-change="(p:number)=>{page=p;load()}" />
  </el-card>
</template>
