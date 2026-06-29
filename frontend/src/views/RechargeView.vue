<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 充值（将挂 /recharge）：充值流水(GET /billing/recharge-log) + 平台充值(POST /billing/recharge·billing.recharge 门控)。
// 逻辑自包含，不依赖未建路由；脱敏/权限沿用 api 返回。
const auth = useAuth()
const log = ref<any[]>([])
const orgs = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
// 弹窗表单字段初始即初始化（防白屏铁律）。
const form = ref<{ orgId: string; type: 'STT' | 'SMS'; qty: number; note: string }>({
  orgId: '',
  type: 'STT',
  qty: 100,
  note: '',
})

// RechargeLog: id/type/delta(+充值/-扣减)/balance/ref/tm（契约字段，无 org/operatedBy/at）。
async function loadLog() {
  loading.value = true
  const { data, error } = await api.GET('/billing/recharge-log', { params: { query: { page: 1, size: 50 } } as any })
  loading.value = false
  if (error) { ElMessage.error('加载充值流水失败'); return }
  log.value = (data as any)?.items ?? []
}

// 组织下拉（GET /orgs）：仅 billing.recharge 权限可见/可充。
async function loadOrgs() {
  if (!auth.has('billing.recharge')) return
  const { data } = await api.GET('/orgs', { params: { query: { page: 1, size: 200 } } as any })
  orgs.value = (data as any)?.items ?? []
}

function openDlg() {
  form.value = { orgId: '', type: 'STT', qty: 100, note: '' }
  dlg.value = true
}

// 平台充值：RechargeInput { orgId, type(STT/SMS), qty>=1, note? } → POST /billing/recharge。
async function recharge() {
  if (!form.value.orgId) { ElMessage.warning('请选择组织'); return }
  const { error } = await api.POST('/billing/recharge', {
    body: {
      orgId: String(form.value.orgId),
      type: form.value.type,
      qty: Number(form.value.qty),
      note: form.value.note || undefined,
    } as any,
  })
  if (error) { ElMessage.error('充值失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('充值成功')
  dlg.value = false
  loadLog()
}

// 类型 → ds-admin .tag 配色（STT=主色/SMS=橙/其余=灰）。
function typeTag(t: string) {
  const s = String(t || '').toUpperCase()
  if (s === 'STT') return 'pri'
  if (s === 'SMS') return 'war'
  if (s === 'EVIDENCE') return 'inf'
  if (s === 'LEGAL') return 'inf'
  return 'inf'
}

onMounted(() => {
  loadLog()
  loadOrgs()
})
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>充值</div>
      <div class="ops">
        <span class="note" style="margin:0">GET /billing/recharge-log · 平台后台充值能力额度（STT 分钟 / SMS 条数）</span>
        <button v-if="auth.has('billing.recharge')" class="btn sm" @click="openDlg">+ 新充值</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:120px">类型</th>
          <th style="width:140px">变动</th>
          <th style="width:140px">余额</th>
          <th>单据</th>
          <th style="width:200px">时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in log" :key="row.id || i">
          <td><span class="tag" :class="typeTag(row.type)">{{ row.type || '—' }}</span></td>
          <td class="num" :style="{ color: row.delta >= 0 ? 'var(--success)' : 'var(--danger)' }">{{ row.delta >= 0 ? '+' : '' }}{{ row.delta != null ? row.delta : '—' }}</td>
          <td class="num">{{ row.balance != null ? row.balance : '—' }}</td>
          <td>{{ row.ref || '—' }}</td>
          <td>{{ row.tm || '—' }}</td>
        </tr>
        <tr v-if="!loading && !log.length">
          <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无充值流水</td>
        </tr>
      </tbody>
    </table>

    <!-- 新充值弹窗（RechargeInput：仅预付项 STT/SMS · qty>=1 · billing.recharge 门控） -->
    <el-dialog v-model="dlg" title="新充值（billing.recharge · 仅平台）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="组织">
          <el-select v-model="form.orgId" placeholder="选择组织" style="width:100%">
            <el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option label="STT 转写分钟" value="STT" />
            <el-option label="SMS 短信条数（仅物业）" value="SMS" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.qty" :min="1" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="recharge">充值</el-button>
      </template>
    </el-dialog>
  </div>
</template>
