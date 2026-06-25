<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M5 质检：风险看板(GET /risks·全量检测) + 处置归属(VL/PL 处置自己员工风险) + 平台复核 + 处置跟踪(仅平台)。
const auth = useAuth()
const isPlatform = computed(() => ['SA', 'SE'].includes(auth.me?.role ?? ''))
const risks = ref<any[]>([])
const tasks = ref<any[]>([])
const loading = ref(false)
const levelType = (l: string) => ({ HIGH: 'danger', MID: 'warning', LOW: 'info' } as any)[l] ?? 'info'

async function load() {
  loading.value = true
  const r = await api.GET('/risks', { params: { query: { page: 1, size: 30 } } as any })
  risks.value = (r.data as any)?.items ?? []
  if (isPlatform.value) {
    const t = await api.GET('/dispose-tasks', { params: { query: { page: 1, size: 20 } } as any })
    tasks.value = (t.data as any)?.items ?? []
  }
  loading.value = false
}

// 处置（归属方 VL/PL）
async function dispose(row: any) {
  const { error } = await api.POST('/risks/{id}/dispose', { params: { path: { id: row.id } }, body: { action: 'mark', note: '已整改' } as any })
  if (error) { ElMessage.error('处置失败：' + ((error as any)?.message ?? '非本组织员工风险/无权限')); return }
  ElMessage.success('已处置'); load()
}
// 上报平台
async function escalate(row: any) {
  const { error } = await api.POST('/risks/{id}/escalate', { params: { path: { id: row.id } }, body: { note: '上报平台复核' } as any })
  if (error) { ElMessage.error('上报失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已上报平台'); load()
}
// 复核（平台）
const rdlg = ref(false); const rform = ref<any>({})
function openReview(row: any) { rform.value = { id: row.id, verdict: 'CONFIRMED', note: '' }; rdlg.value = true }
async function submitReview() {
  const { error } = await api.POST('/risks/{id}/review', { params: { path: { id: rform.value.id } }, body: { verdict: rform.value.verdict, note: rform.value.note } as any })
  if (error) { ElMessage.error('复核失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('复核完成'); rdlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <el-card header="质检风险看板（GET /risks · 全量检测 · 处置归属/平台复核）">
    <el-table v-loading="loading" :data="risks" border size="small">
      <el-table-column label="级别" width="80"><template #default="{row}"><el-tag size="small" :type="levelType(row.level)">{{ row.level }}</el-tag></template></el-table-column>
      <el-table-column prop="type" label="风险类型" />
      <el-table-column prop="collector" label="催收员" width="100" />
      <el-table-column prop="segmentTs" label="片段" width="90" />
      <el-table-column label="复核" width="120"><template #default="{row}"><el-tag v-if="row.reviewed" size="small" type="success">{{ row.reviewed }}</el-tag><span v-else style="color:#909399">待复核</span></template></el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <template v-if="auth.has('qc.dispose')">
            <el-button size="small" @click="dispose(row)">处置</el-button>
            <el-button size="small" @click="escalate(row)">上报</el-button>
          </template>
          <el-button v-if="auth.has('qc.review')" size="small" type="primary" @click="openReview(row)">复核</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="处置归属(BR-M5-07a)：服务商 VL 处置本商催收员风险、物业 PL 处置本物业协调员风险；平台只复核(CONFIRMED/FALSE_POSITIVE/ESCALATED)。" />

    <template v-if="isPlatform">
      <el-divider content-position="left">处置任务跟踪（GET /dispose-tasks · 仅平台监管视图 BR-M5-07b）</el-divider>
      <el-table :data="tasks" border size="small">
        <el-table-column prop="provider" label="服务商" /><el-table-column prop="taskType" label="任务类型" />
        <el-table-column prop="status" label="状态" width="120" /><el-table-column prop="tm" label="时间" />
      </el-table>
    </template>

    <el-dialog v-model="rdlg" title="平台复核（GET /risks/{id}/review）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="判定">
          <el-select v-model="rform.verdict">
            <el-option label="确认属实 CONFIRMED" value="CONFIRMED" />
            <el-option label="误报 FALSE_POSITIVE" value="FALSE_POSITIVE" />
            <el-option label="升级 ESCALATED" value="ESCALATED" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="rform.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="rdlg=false">取消</el-button><el-button type="primary" @click="submitReview">提交复核</el-button></template>
    </el-dialog>
  </el-card>
</template>
