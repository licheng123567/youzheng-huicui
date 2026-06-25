<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// GET /batches → BatchView(平台双线/物业只收佣/服务商只付佣)。资金双线字段级隔离。SA 可派单(M3)。
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

// M3 派单：POST /batches/{id}/dispatch（DispatchInput 必填 mode/providerId/payOutRate）。
const dlg = ref(false)
const form = ref({ batchId: '', providerId: '', payOutRate: 20 })
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

onMounted(load)
</script>

<template>
  <el-card :header="`批次（GET /batches · 共 ${total}）`">
    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="批次号" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column label="收佣比例" width="120">
        <template #default="{ row }">{{ row.commInRate != null ? (row.commInRate * 100).toFixed(2) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="付佣比例" width="120">
        <template #default="{ row }">{{ row.payOutRate != null ? (row.payOutRate * 100).toFixed(2) + '%' : '—（物业视角不可见）' }}</template>
      </el-table-column>
      <el-table-column v-if="auth.has('case.dispatch')" label="操作" width="100">
        <template #default="{ row }">
          <el-button size="small" type="primary" :loading="acting===row.id" @click="openDispatch(row.id)">派单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dlg" title="派单（POST /batches/{id}/dispatch）" width="420px">
      <el-form label-width="120px">
        <el-form-item label="服务商 org id"><el-input v-model="form.providerId" placeholder="如捷信催收的 org id" /></el-form-item>
        <el-form-item label="付佣比例(小数)"><el-input-number v-model="form.payOutRate" :min="0" :max="1" :step="0.01" /><span style="margin-left:8px;color:#909399">0.2=20%（须≤收佣，防倒挂）</span></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg=false">取消</el-button>
        <el-button type="primary" :loading="acting===form.batchId" @click="submitDispatch">整批派单</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
