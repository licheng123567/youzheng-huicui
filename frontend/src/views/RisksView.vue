<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { riskLevelLabel, riskVerdictLabel, disposeTaskStatusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// M5 质检：风险看板(GET /risks·全量检测) + 处置归属(VL/PL 处置自己员工风险) + 平台复核 + 处置跟踪(仅平台)。
const auth = useAuth()
const isPlatform = computed(() => ['SA', 'SE'].includes(auth.me?.role ?? ''))
const risks = ref<any[]>([])
const tasks = ref<any[]>([])
const loading = ref(false)
const levelType = (l: string) => ({ HIGH: 'danger', MID: 'warning', LOW: 'info' } as any)[l] ?? 'info'
// 纯展示：风险级别 → ds-admin .tag 配色（dan/war/inf），仅用于 markup 着色
const levelTag = (l: string) => ({ HIGH: 'dan', MID: 'war', LOW: 'inf' } as any)[l] ?? 'inf'

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

// 处置（归属方 VL/PL）：弹窗选 action(mark/to_qc/notify) + 填 note(可选)
const ddlg = ref(false); const dform = ref<any>({})
function openDispose(row: any) { dform.value = { id: row.id, action: 'mark', note: '' }; ddlg.value = true }
async function submitDispose() {
  const { error } = await api.POST('/risks/{id}/dispose', { params: { path: { id: dform.value.id } }, body: { action: dform.value.action, note: dform.value.note } as any })
  if (error) { ElMessage.error('处置失败：' + ((error as any)?.message ?? '非本组织员工风险/无权限')); return }
  ElMessage.success('已处置'); ddlg.value = false; load()
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
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>质检风险看板</div>
      <div class="ops"><span class="note" style="margin:0">GET /risks · 全量检测 · 处置归属 / 平台复核</span></div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:80px">级别</th>
          <th>风险类型</th>
          <th style="width:100px">催收员</th>
          <th style="width:90px">片段</th>
          <th style="width:120px">复核</th>
          <th style="width:220px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in risks" :key="row.id">
          <td><span class="tag" :class="levelTag(row.level)" :title="row.level">{{ riskLevelLabel(row.level) }}</span></td>
          <td>{{ row.type || '—' }}</td>
          <td>{{ row.collector || '—' }}</td>
          <td>{{ row.segmentTs || '—' }}</td>
          <td>
            <span v-if="row.reviewed" class="tag suc" :title="row.reviewed">{{ riskVerdictLabel(row.reviewed) }}</span>
            <span v-else style="color:var(--sec)">待复核</span>
          </td>
          <td>
            <button v-if="auth.has('qc.dispose')" class="btn txt" @click="openDispose(row)">处置</button>
            <button v-if="auth.has('qc.escalate')" class="btn txt" @click="escalate(row)">上报</button>
            <button v-if="auth.has('qc.review')" class="btn txt" @click="openReview(row)">复核</button>
          </td>
        </tr>
        <tr v-if="!loading && !risks.length">
          <td colspan="6" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="alert info">处置归属(BR-M5-07a)：服务商 VL 处置本商催收员风险、物业 PL 处置本物业协调员风险；平台只复核(CONFIRMED/FALSE_POSITIVE/ESCALATED)。</div>

    <template v-if="isPlatform">
      <div class="card-h" style="margin-top:22px">
        <div class="t"><span class="bar"></span>处置任务跟踪</div>
        <div class="ops"><span class="note" style="margin:0">GET /dispose-tasks · 仅平台监管视图 BR-M5-07b</span></div>
      </div>
      <table>
        <thead>
          <tr>
            <th>服务商</th>
            <th>任务类型</th>
            <th style="width:120px">状态</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td>{{ t.provider || '—' }}</td>
            <td>{{ t.taskType || '—' }}</td>
            <td :title="t.status">{{ disposeTaskStatusLabel(t.status) }}</td>
            <td>{{ t.tm || '—' }}</td>
          </tr>
          <tr v-if="!tasks.length">
            <td colspan="4" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </template>

    <DsDrawer v-model="ddlg" title="风险处置">
      <el-form label-width="80px">
        <el-form-item label="处置方式">
          <el-select v-model="dform.action" style="width:100%">
            <el-option label="标记 mark" value="mark" />
            <el-option label="转质检 to_qc" value="to_qc" />
            <el-option label="通知 notify" value="notify" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="dform.note" type="textarea" :rows="2" placeholder="可选" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ddlg=false">取消</el-button><el-button type="primary" @click="submitDispose">提交处置</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="rdlg" title="平台复核">
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
    </DsDrawer>
  </div>
</template>
