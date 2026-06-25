<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// GET /batches → BatchView(平台双线/物业只收佣/服务商只付佣)。SA 派单(M3)；物业可导入批次/作废(批次2)。
const auth = useAuth()
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const acting = ref('')

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/batches', { params: { query: { page: 1, size: 20 } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}

// M3 派单
const dlg = ref(false)
const form = ref({ batchId: '', providerId: '', payOutRate: 0.2 })
function openDispatch(id: string) { form.value = { batchId: id, providerId: '', payOutRate: 0.2 }; dlg.value = true }
async function submitDispatch() {
  if (!form.value.providerId) { ElMessage.warning('请填服务商 org id'); return }
  acting.value = form.value.batchId
  const { error } = await api.POST('/batches/{id}/dispatch', {
    params: { path: { id: form.value.batchId } },
    body: { mode: 'WHOLE', providerId: form.value.providerId, payOutRate: form.value.payOutRate } as any,
  })
  acting.value = ''; dlg.value = false
  if (error) { ElMessage.error('派单失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已派单'); load()
}

// 批次2 导入：POST /batches/import（BatchImport: projectId/commInRate(分数)/rows[CaseImportRow]）
const impDlg = ref(false)
const emptyRow = () => ({ acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, arrearPeriod: '' })
const imp = ref<any>({ projectId: '', commInRate: 0.3, rows: [emptyRow()] })
function openImport() { imp.value = { projectId: '', commInRate: 0.3, rows: [emptyRow()] }; impDlg.value = true }
async function submitImport() {
  if (!imp.value.projectId) { ElMessage.warning('请填项目 id'); return }
  const rows = imp.value.rows.map((r: any) => ({ acctNo: r.acctNo, ownerName: r.ownerName, phone: r.phone, room: r.room, dueCents: Math.round(r.dueYuan * 100), arrearPeriod: r.arrearPeriod }))
  const { error } = await api.POST('/batches/import', { body: { projectId: String(imp.value.projectId), commInRate: Number(imp.value.commInRate), rows } as any })
  if (error) { ElMessage.error('导入失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(`已导入 ${rows.length} 件`); impDlg.value = false; load()
}

// 批次2 作废：POST /batches/{id}/void（留痕）
async function voidBatch(row: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt('作废原因（留痕，不可逆）', '作废批次 ' + row.code, { inputValidator: (v) => !!v || '原因必填' })
    const { error } = await api.POST('/batches/{id}/void', { params: { path: { id: row.id } }, body: { reason } as any })
    if (error) { ElMessage.error('作废失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已作废'); load()
  } catch { /* 取消 */ }
}
onMounted(load)
</script>

<template>
  <el-card :header="`批次（GET /batches · 共 ${total}）`">
    <el-button v-if="auth.has('batch.import')" type="primary" size="small" style="margin-bottom:10px" @click="openImport">导入批次</el-button>
    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="code" label="批次号" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="收佣比例" width="100">
        <template #default="{ row }">{{ row.commInRate != null ? (row.commInRate * 100).toFixed(2) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="付佣比例" width="120">
        <template #default="{ row }">{{ row.payOutRate != null ? (row.payOutRate * 100).toFixed(2) + '%' : '—（物业视角不可见）' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button v-if="auth.has('case.dispatch')" size="small" type="primary" :loading="acting===row.id" @click="openDispatch(row.id)">派单</el-button>
          <el-button v-if="auth.has('case.void')" size="small" type="danger" plain @click="voidBatch(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 派单 -->
    <el-dialog v-model="dlg" title="派单（POST /batches/{id}/dispatch）" width="420px">
      <el-form label-width="120px">
        <el-form-item label="服务商 org id"><el-input v-model="form.providerId" placeholder="如捷信催收的 org id" /></el-form-item>
        <el-form-item label="付佣比例(小数)"><el-input-number v-model="form.payOutRate" :min="0" :max="1" :step="0.01" /><span style="margin-left:8px;color:#909399">0.2=20%（须≤收佣，防倒挂）</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" :loading="acting===form.batchId" @click="submitDispatch">整批派单</el-button></template>
    </el-dialog>

    <!-- 导入批次 -->
    <el-dialog v-model="impDlg" title="导入批次（POST /batches/import · 创建批次+案件）" width="760px">
      <el-form :inline="true" label-width="90px">
        <el-form-item label="项目 id"><el-input v-model="imp.projectId" style="width:120px" placeholder="如 1" /></el-form-item>
        <el-form-item label="收佣比例"><el-input-number v-model="imp.commInRate" :min="0" :max="1" :step="0.01" /><span style="margin-left:6px;color:#909399">分数 0.3=30%</span></el-form-item>
      </el-form>
      <el-table :data="imp.rows" border size="small">
        <el-table-column label="户号"><template #default="{row}"><el-input v-model="row.acctNo" size="small" /></template></el-table-column>
        <el-table-column label="姓名"><template #default="{row}"><el-input v-model="row.ownerName" size="small" /></template></el-table-column>
        <el-table-column label="手机"><template #default="{row}"><el-input v-model="row.phone" size="small" /></template></el-table-column>
        <el-table-column label="房号"><template #default="{row}"><el-input v-model="row.room" size="small" /></template></el-table-column>
        <el-table-column label="应收(元)" width="110"><template #default="{row}"><el-input-number v-model="row.dueYuan" size="small" :min="0" :controls="false" style="width:90px" /></template></el-table-column>
        <el-table-column label="欠费期" width="100"><template #default="{row}"><el-input v-model="row.arrearPeriod" size="small" placeholder="2025-01" /></template></el-table-column>
        <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" @click="imp.rows.splice($index,1)" :disabled="imp.rows.length<=1">×</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" text type="primary" style="margin-top:6px" @click="imp.rows.push(emptyRow())">+ 添加行</el-button>
      <template #footer><el-button @click="impDlg=false">取消</el-button><el-button type="primary" @click="submitImport">导入</el-button></template>
    </el-dialog>
  </el-card>
</template>
