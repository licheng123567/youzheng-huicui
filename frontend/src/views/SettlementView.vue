<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M9 结算·资金双线：side=IN(收佣 平台↔物业) / OUT(付佣 平台↔服务商)。按角色门控可见线别。
const auth = useAuth()
const role = computed(() => auth.me?.role)
// 平台见双线；物业仅 IN；服务商仅 OUT
const sides = computed(() => (role.value === 'PL' || role.value === 'PC') ? ['IN'] : (role.value === 'VL' || role.value === 'CO') ? ['OUT'] : ['IN', 'OUT'])
const side = ref<'IN' | 'OUT'>('IN')
const rollup = ref<any[]>([])
const prs = ref<any[]>([])
const loading = ref(false)
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => (r == null ? '—' : (r * 100).toFixed(2) + '%')

async function load() {
  loading.value = true
  const rk = await api.GET('/recon/rollup', { params: { query: { side: side.value, page: 1, size: 20 } } as any })
  rollup.value = (rk.data as any)?.items ?? []
  const pr = await api.GET('/payment-requests', { params: { query: { side: side.value, page: 1, size: 20 } } as any })
  loading.value = false
  if (pr.error) { ElMessage.error('加载支付申请单失败（可能跨线 403）'); prs.value = []; return }
  prs.value = (pr.data as any)?.items ?? []
}

// 完成（必带凭证 BR-M9-12d）
const cdlg = ref(false); const cform = ref<any>({})
function openComplete(row: any) { cform.value = { id: row.id, version: row.version, type: side.value === 'IN' ? 'RECEIPT' : 'PAYMENT', fileUrl: 'https://example.com/voucher.pdf' }; cdlg.value = true }
async function submitComplete() {
  const { error } = await api.POST('/payment-requests/{id}/complete', { params: { path: { id: cform.value.id } }, body: { voucher: { type: cform.value.type, fileUrl: cform.value.fileUrl }, version: cform.value.version } as any })
  if (error) { ElMessage.error('完成失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已完成（凭证已留存）'); cdlg.value = false; load()
}
// 撤销/撤回
async function revoke(row: any) {
  const { error } = await api.POST('/payment-requests/{id}/revoke', { params: { path: { id: row.id } }, body: { version: row.version, reason: '前端撤销' } as any })
  if (error) { ElMessage.error('撤销失败：' + ((error as any)?.message ?? '已PAID不可撤')); return }
  ElMessage.success('已撤销，明细释放'); load()
}
onMounted(() => { side.value = sides.value[0] as any; load() })
</script>

<template>
  <el-card header="结算 · 资金双线（IN 收佣 平台↔物业 / OUT 付佣 平台↔服务商）">
    <el-radio-group v-model="side" style="margin-bottom:12px" @change="load">
      <el-radio-button v-for="s in sides" :key="s" :label="s">{{ s==='IN'?'收佣线(IN)':'付佣线(OUT)' }}</el-radio-button>
    </el-radio-group>

    <el-divider content-position="left">对账汇总（GET /recon/rollup）</el-divider>
    <el-table :data="rollup" border size="small">
      <el-table-column prop="batchCode" label="批次" /><el-table-column label="回款基数"><template #default="{row}">{{ yuan(row.baseCents) }}</template></el-table-column>
      <el-table-column label="比例"><template #default="{row}">{{ pct(row.rate ?? row.commRate) }}</template></el-table-column>
      <el-table-column label="应结"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
    </el-table>

    <el-divider content-position="left">支付申请单（GET /payment-requests?side={{side}}）</el-divider>
    <el-table v-loading="loading" :data="prs" border size="small">
      <el-table-column prop="code" label="单号" /><el-table-column prop="status" label="状态" width="90">
        <template #default="{row}"><el-tag size="small" :type="row.status==='PAID'?'success':row.status==='VOIDED'?'info':'warning'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="基数"><template #default="{row}">{{ yuan(row.baseCents) }}</template></el-table-column>
      <el-table-column label="比例"><template #default="{row}">{{ pct(row.commRate) }}</template></el-table-column>
      <el-table-column label="应结佣金"><template #default="{row}">{{ yuan(row.commCents) }}</template></el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <template v-if="row.status==='PENDING'">
            <el-button size="small" type="primary" @click="openComplete(row)">{{ side==='IN'?'确认收款':'支付完成' }}</el-button>
            <el-button size="small" @click="revoke(row)">{{ side==='IN'?'撤销':'撤回' }}</el-button>
          </template>
          <span v-else style="color:#909399">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="cdlg" :title="(side==='IN'?'确认收款':'支付完成')+'（必带凭证 BR-M9-12d）'" width="440px">
      <el-form label-width="100px">
        <el-form-item label="凭证类型"><el-tag>{{ cform.type==='RECEIPT'?'收款凭证':'支付凭证' }}</el-tag></el-form-item>
        <el-form-item label="凭证文件 URL"><el-input v-model="cform.fileUrl" /></el-form-item>
        <el-form-item label="版本(乐观锁)"><el-input-number v-model="cform.version" :min="1" disabled /></el-form-item>
      </el-form>
      <template #footer><el-button @click="cdlg=false">取消</el-button><el-button type="primary" @click="submitComplete">完成</el-button></template>
    </el-dialog>
  </el-card>
</template>
