<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 计费(M9-B)：能力用量(GET /billing/usage·按次 STT/SMS/存证/法务) + 充值流水(recharge-log) + 平台充值(recharge)。
const auth = useAuth()
const usage = ref<any[]>([])
const log = ref<any[]>([])
const orgs = ref<any[]>([])
const dlg = ref(false)
const form = ref<any>({ orgId: '', type: 'STT', qty: 100, note: '' })

async function load() {
  const u = await api.GET('/billing/usage', { params: { query: { page: 1, size: 20 } } as any })
  usage.value = (u.data as any)?.items ?? []
  const l = await api.GET('/billing/recharge-log', { params: { query: { page: 1, size: 20 } } as any })
  log.value = (l.data as any)?.items ?? []
  if (auth.has('billing.recharge')) {
    const o = await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })
    orgs.value = (o.data as any)?.items ?? []
  }
}
async function recharge() {
  const { error } = await api.POST('/billing/recharge', { body: { orgId: String(form.value.orgId), type: form.value.type, qty: Number(form.value.qty), note: form.value.note } as any })
  if (error) { ElMessage.error('充值失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('充值成功'); dlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <el-card header="计费 · 能力用量与充值（按次：STT 转写/SMS 短信/存证/法务 — BR-M9-06a）">
    <el-button v-if="auth.has('billing.recharge')" type="primary" size="small" style="margin-bottom:10px" @click="dlg=true">平台充值</el-button>

    <el-divider content-position="left">能力用量（GET /billing/usage）</el-divider>
    <el-table :data="usage" border size="small">
      <el-table-column prop="type" label="能力" width="120" />
      <el-table-column label="用量"><template #default="{row}">{{ row.qty }} {{ row.unit }}</template></el-table-column>
      <el-table-column prop="caseId" label="案件" width="90" />
      <el-table-column prop="occurredAt" label="时间" />
    </el-table>

    <el-divider content-position="left">充值流水（GET /billing/recharge-log）</el-divider>
    <el-table :data="log" border size="small">
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column label="变动"><template #default="{row}"><span :style="{color: row.delta>=0 ? '#67c23a':'#f56c6c'}">{{ row.delta>=0?'+':'' }}{{ row.delta }}</span></template></el-table-column>
      <el-table-column prop="balance" label="余额" />
      <el-table-column prop="ref" label="单据" /><el-table-column prop="tm" label="时间" />
    </el-table>

    <el-dialog v-model="dlg" title="平台充值（billing.recharge·仅平台）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="组织"><el-select v-model="form.orgId" placeholder="选择组织"><el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="STT 转写分钟" value="STT" /><el-option label="SMS 短信条数(仅物业)" value="SMS" /></el-select></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.qty" :min="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="recharge">充值</el-button></template>
    </el-dialog>
  </el-card>
</template>
