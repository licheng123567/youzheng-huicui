<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 操作日志（M-07·P-ORG-08）：GET /audit-log 只读列表 + from/to 范围筛选 + 分页。
// 数据范围(x-data-scope=range)由后端裁剪，前端纯只读、无写端点。
// 代操作(proxyFor 非空 BR-M1-15)以「代操作」标签高亮；before/after 快照可展开 JSON。
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
// 日期范围筛选：el-date-picker 双向绑定数组（初始即初始化两槽，防展开/赋值时下标越界）。
const range = ref<string[]>(['', ''])

async function load() {
  loading.value = true
  const query: any = { page: page.value, size }
  if (range.value && range.value[0]) query.from = range.value[0]
  if (range.value && range.value[1]) query.to = range.value[1]
  const { data, error } = await api.GET('/audit-log', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载操作日志失败：' + ((error as any)?.message ?? '')); return }
  rows.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function onSearch() { page.value = 1; load() }
function onReset() { range.value = ['', '']; page.value = 1; load() }
function onPage(p: number) { page.value = p; load() }

// before/after 为 object|null，渲染需容错；空快照显示占位短横。
function snapshot(v: any): string {
  if (v === null || v === undefined) return '—'
  try { return JSON.stringify(v, null, 2) } catch (e) { return String(v) }
}
function hasSnapshot(row: any): boolean {
  return !!(row && (row.before || row.after))
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>操作日志</div>
      <div class="ops"><span class="note" style="margin:0">GET /audit-log · 只读 · range 范围裁剪 · 共 {{ total }}</span></div>
    </div>

    <!-- 范围筛选：from/to（datetimerange 复杂交互保留 EL date-picker） -->
    <div class="search" style="margin-bottom:14px">
      <div class="fi">
        <span>时间范围</span>
        <el-date-picker
          v-model="range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="起始时间 from"
          end-placeholder="结束时间 to"
          unlink-panels
        />
      </div>
      <div class="fi">
        <button class="btn" @click="onSearch">查询</button>
        <button class="btn df" @click="onReset">重置</button>
      </div>
    </div>

    <!-- 带 type="expand" 快照展开的复杂表格：保留 el-table 原样，仅换标签/壳 -->
    <el-table :data="rows" v-loading="loading" border size="small">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding:8px 16px">
            <div v-if="!hasSnapshot(row)" class="note" style="margin:0">无变更快照</div>
            <template v-else>
              <div class="lbl" style="margin-bottom:4px">变更前 before</div>
              <pre class="snap">{{ snapshot(row.before) }}</pre>
              <div class="lbl" style="margin-bottom:4px">变更后 after</div>
              <pre class="snap" style="margin:0">{{ snapshot(row.after) }}</pre>
            </template>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="tm" label="时间" width="180" />
      <el-table-column prop="actor" label="操作人" width="140" />
      <el-table-column label="动作" width="160">
        <template #default="{ row }">
          <span class="tag inf">{{ row.action || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="目标" min-width="180">
        <template #default="{ row }">
          <span>{{ row.target || '—' }}</span>
          <span v-if="row.targetType" class="note" style="margin:0">（{{ row.targetType }}<span v-if="row.targetId">#{{ row.targetId }}</span>）</span>
        </template>
      </el-table-column>
      <el-table-column label="范围" width="120">
        <template #default="{ row }">
          <span v-if="row.scope">{{ row.scope }}</span>
          <span v-if="row.proxyFor" class="tag war" style="margin-left:4px">代操作</span>
        </template>
      </el-table-column>
      <el-table-column prop="proxyFor" label="代操作对象" width="140">
        <template #default="{ row }">{{ row.proxyFor || '—' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140">
        <template #default="{ row }">{{ row.reason || '—' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130">
        <template #default="{ row }">{{ row.ip || '—' }}</template>
      </el-table-column>
      <el-table-column prop="traceId" label="TraceId" width="160">
        <template #default="{ row }">{{ row.traceId || '—' }}</template>
      </el-table-column>
    </el-table>

    <div class="alert info">系统级操作日志按当前角色数据范围(range)裁剪；代操作全程留痕，被代方可见。</div>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="onPage(page - 1)">‹</div>
      <div class="pg on">{{ page }}</div>
      <div class="pg" @click="onPage(page + 1)">›</div>
    </div>
  </div>
</template>

<style scoped>
.snap {
  margin: 0 0 10px;
  background: #f7f7f9;
  border: 1px solid var(--bd);
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
}
</style>
