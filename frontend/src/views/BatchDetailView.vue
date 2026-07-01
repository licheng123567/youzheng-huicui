<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import CoordinatorPicker from '../components/CoordinatorPicker.vue'
import { caseStatusLabel, reduceDecideLabel, poolLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// 批次详情 /batches/:id：GET /batches/{id}(双线视角) + GET /cases?batchId 案件清单 + 减免档位 + 协调员 + 作战手册。
// 含 M2-B：批次协调员维护 / 减免覆盖编辑·恢复继承 / 手册采纳·恢复继承 / BR-M2-18b 覆盖同步(drift+一键同步)。
const route = useRoute(); const router = useRouter()
const auth = useAuth()
const { showCommInRate, showPayOutRate, ratePct } = useRoleFields()
const bid = String(route.params.id)
const b = ref<any>(null); const cases = ref<any[]>([]); const tiers = ref<any[]>([])
const tiersSource = ref<string | null>(null) // INHERITED | CUSTOM | null(无权限)
const tiersPermDenied = ref(false)
const yuan = (c?: number | null) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const pct = (r?: number) => r != null ? (r * 100).toFixed(1) + '%' : '—'
const STATUS_TAG: Record<string, string> = { SETTLED: 'suc', IN_PROGRESS: 'pri', PROMISED: 'war', PENDING_DISPATCH: 'inf', PROVIDER_SEA: 'inf', WITHDRAWN: 'inf', BAD_DEBT: 'dan', VOIDED: 'dan' }
const statusTag = (s?: string) => STATUS_TAG[s ?? ''] ?? 'inf'
const sourceLabel = (s: string | null) => s === 'CUSTOM' ? '批次自定义' : s === 'INHERITED' ? '继承项目默认' : ''
const batchTab = ref<'cases' | 'props'>('cases')

async function loadBatch() {
  const { data, error } = await api.GET('/batches/{id}', { params: { path: { id: bid } } })
  if (error || !data) { ElMessage.error('批次加载失败'); return }
  b.value = data
}
async function loadReduceTiers() {
  const rt = await api.GET('/batches/{id}/reduce-tiers', { params: { path: { id: bid } } })
  if ((rt.response?.status === 403) || (rt.error && (rt.error as any)?.status === 403)) {
    tiersPermDenied.value = true
  } else if (!rt.error && rt.data) {
    tiersPermDenied.value = false
    tiers.value = (rt.data as any)?.tiers ?? []
    tiersSource.value = (rt.data as any)?.source ?? null
  }
}
async function loadAll() {
  await loadBatch()
  cases.value = ((await api.GET('/cases', { params: { query: { batchId: bid, page: 1, size: 100 } } as any })).data as any)?.items ?? []
  await loadReduceTiers()
  await loadPlaybook()
}
function openCase(c: any) { router.push(`/cases/${c.id}`) }

// ── BC-01 批次协调员维护(PUT /batches/{id}/coordinators · batch.import) ──
const coordDlg = ref(false)
function openCoord() { coordDlg.value = true }
async function saveCoordinators(coordinatorIds: string[]) {
  const { error } = await api.PUT('/batches/{id}/coordinators', { params: { path: { id: bid } }, body: { coordinatorIds } })
  if (error) { ElMessage.error('保存协调员失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已更新批次协调员'); coordDlg.value = false; loadBatch()
}

// ── BC-02 批次减免覆盖编辑 / 清除恢复继承(PUT /batches/{id}/reduce-tiers · reduce.policy.edit) ──
const reduceDlg = ref(false)
// 编辑模型：capYuan 元展示、提交 ×100 存 capCents
const reduceRows = ref<{ discount: string; capYuan: number | null; waivePenalty: boolean; decide: string }[]>([])
function emptyTier() { return { discount: '', capYuan: null as number | null, waivePenalty: false, decide: 'COLLECTOR_SELF' } }
function openReduce() {
  reduceRows.value = (tiers.value ?? []).map((t: any) => ({
    discount: t.discount ?? '',
    capYuan: t.capCents != null ? t.capCents / 100 : null,
    waivePenalty: !!t.waivePenalty,
    decide: t.decide ?? 'COLLECTOR_SELF',
  }))
  if (!reduceRows.value.length) reduceRows.value = [emptyTier()]
  reduceDlg.value = true
}
async function saveReduce() {
  const payload = reduceRows.value
    .filter((r) => r.discount && r.discount.trim())
    .map((r) => ({
      discount: r.discount,
      capCents: r.capYuan != null ? Math.round(r.capYuan * 100) : null,
      waivePenalty: r.waivePenalty,
      decide: r.decide as any,
    }))
  const { error } = await api.PUT('/batches/{id}/reduce-tiers', { params: { path: { id: bid } }, body: { tiers: payload } })
  if (error) { ElMessage.error('保存减免覆盖失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已保存批次自定义减免'); reduceDlg.value = false; loadReduceTiers(); loadBatch()
}
async function clearReduce() {
  try {
    await ElMessageBox.confirm('清除批次自定义减免，恢复继承项目默认？', '恢复继承', { type: 'warning' })
  } catch { return }
  // 空数组=清除自定义恢复继承
  const { error } = await api.PUT('/batches/{id}/reduce-tiers', { params: { path: { id: bid } }, body: { tiers: [] } })
  if (error) { ElMessage.error('恢复继承失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已恢复继承项目默认减免'); loadReduceTiers(); loadBatch()
}
// BR-M2-18b 一键同步减免：放弃自定义重新继承项目最新(清 reduceDrift)
async function syncReduce() {
  const { error } = await api.POST('/batches/{id}/reduce-tiers:sync', { params: { path: { id: bid } } })
  if (error) { ElMessage.error('同步失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已同步为项目最新减免'); loadReduceTiers(); loadBatch()
}

// ── BC-03 批次作战手册查看/采纳/恢复继承(GET·POST /batches/{id}/playbook · playbook.adopt) ──
const playbook = ref<any>(null)
const playbookSource = ref<string | null>(null)
const pbDlg = ref(false)
const pbForm = ref<{ version: string; content: string }>({ version: '', content: '' })
async function loadPlaybook() {
  const { data, error } = await api.GET('/batches/{id}/playbook', { params: { path: { id: bid } } })
  if (error || !data) { playbook.value = null; playbookSource.value = null; return }
  playbookSource.value = (data as any)?.source ?? null
  playbook.value = (data as any)?.playbook ?? null
}
function openPlaybook() { pbForm.value = { version: playbook.value?.version ?? 'v1.0', content: playbook.value?.content ?? '' }; pbDlg.value = true }
async function adoptPlaybook() {
  const { error } = await api.POST('/batches/{id}/playbook', { params: { path: { id: bid } }, body: { version: pbForm.value.version, content: pbForm.value.content } })
  if (error) { ElMessage.error('采纳失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已采纳为批次自定义手册'); pbDlg.value = false; loadPlaybook()
}
async function restorePlaybook() {
  try {
    await ElMessageBox.confirm('清除批次自定义手册，恢复继承项目？', '恢复继承', { type: 'warning' })
  } catch { return }
  // content=null=清除自定义恢复继承
  const { error } = await api.POST('/batches/{id}/playbook', { params: { path: { id: bid } }, body: { content: null } })
  if (error) { ElMessage.error('恢复继承失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已恢复继承项目手册'); loadPlaybook()
}
// BR-M2-18b 一键同步手册：放弃自定义重新继承项目最新(清 playbookDrift)
async function syncPlaybook() {
  const { error } = await api.POST('/batches/{id}/playbook:sync', { params: { path: { id: bid } } })
  if (error) { ElMessage.error('同步失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已同步为项目最新手册'); loadPlaybook(); loadBatch()
}

onMounted(loadAll)
</script>

<template>
  <div v-if="b" class="batch-detail">
    <!-- 顶部标题 + 返回 -->
    <div class="card" style="margin-bottom:12px">
      <div class="card-h">
        <div class="t"><span class="bar"></span>{{ b.code }} — 批次明细</div>
        <div class="ops">
          <button class="btn df sm" @click="router.push('/cases')">← 返回案件管理</button>
        </div>
      </div>

      <!-- KPI 卡片 -->
      <div class="kpis" style="grid-template-columns:repeat(9,1fr);margin-bottom:0">
        <div class="kpi"><div class="n">{{ b.totalCases ?? cases.length }}</div><div class="l">案件总数</div></div>
        <div class="kpi"><div class="n">{{ cases.filter((c:any) => c.status === 'IN_PROGRESS').length }}</div><div class="l">在催</div></div>
        <div class="kpi"><div class="n">{{ cases.filter((c:any) => c.status === 'SETTLED').length }}</div><div class="l">已结清</div></div>
        <div class="kpi"><div class="n">{{ cases.filter((c:any) => c.status === 'WITHDRAWN' || c.status === 'BAD_DEBT' || c.status === 'VOIDED').length }}</div><div class="l">撤案/坏账</div></div>
        <div class="kpi"><div class="n">{{ pct(b.repayRate) }}</div><div class="l">回款率</div></div>
        <div class="kpi"><div class="n">{{ yuan(b.dueTotalCents) }}</div><div class="l">应收总额</div></div>
        <div class="kpi"><div class="n">{{ yuan(b.repaidTotalCents) }}</div><div class="l">已收总额</div></div>
        <div class="kpi"><div class="n">{{ b.legalCount ?? '—' }}</div><div class="l">法务数</div></div>
        <div class="kpi"><div class="n">{{ b.progress ?? '—' }}</div><div class="l">催收进度</div></div>
      </div>

      <!-- 佣金比例标签 -->
      <div style="margin:10px 0 4px">
        <span v-if="showCommInRate" class="tag war" style="margin-right:8px">收佣比例 {{ ratePct(b.commInRate) }}</span>
        <span v-if="showPayOutRate" class="tag suc">付佣比例 {{ ratePct(b.payOutRate) }}</span>
      </div>

      <!-- Tab 切换 -->
      <div class="segctrl" style="margin-top:12px">
        <span :class="{ on: batchTab === 'cases' }" @click="batchTab = 'cases'">案件明细</span>
        <span :class="{ on: batchTab === 'props' }" @click="batchTab = 'props'">批次属性</span>
      </div>
    </div>

    <!-- 批次属性 Tab -->
    <template v-if="batchTab === 'props'">
      <!-- 作战手册 -->
      <div class="card">
        <div class="sec-title">作战手册（批次级） <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">继承项目 或 自定义覆盖</span></div>
        <span v-if="playbookSource" class="tag inf" style="font-weight:400;margin-right:8px">{{ sourceLabel(playbookSource) }}</span>
        <button v-if="auth.has('playbook.adopt')" class="btn txt" @click="openPlaybook">采纳/编辑</button>
        <button v-if="auth.has('playbook.adopt') && playbookSource==='CUSTOM'" class="btn txt" @click="restorePlaybook">恢复继承</button>
        <div v-if="b.playbookDrift" class="alert warn" style="margin-top:8px">
          项目级作战手册已更新，当前批次自定义有差异
          <button v-if="auth.has('playbook.adopt')" class="btn sm" style="margin-left:auto" @click="syncPlaybook">一键同步为项目最新</button>
        </div>
        <div v-if="playbook" class="desc" style="margin-top:8px">
          <div class="r"><div class="k">版本</div><div class="v">{{ playbook.version ?? '—' }}</div></div>
          <div class="r"><div class="k">内容</div><div class="v"><div class="pb-content">{{ playbook.content ?? '（尚无手册）' }}</div></div></div>
        </div>
        <div v-else class="note" style="margin-top:8px">尚无作战手册。</div>
      </div>

      <!-- 减免政策 -->
      <div class="card">
        <div class="sec-title">减免政策（批次级） <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">继承项目 或 自定义覆盖</span></div>
        <span v-if="tiersSource" class="tag inf" style="font-weight:400;margin-right:8px">{{ sourceLabel(tiersSource) }}</span>
        <button v-if="!tiersPermDenied && auth.has('reduce.policy.edit')" class="btn txt" @click="openReduce">自定义覆盖</button>
        <button v-if="!tiersPermDenied && auth.has('reduce.policy.edit') && tiersSource==='CUSTOM'" class="btn txt" @click="clearReduce">恢复继承</button>
        <div v-if="b.reduceDrift" class="alert warn" style="margin-top:8px">
          项目级减免已更新，当前批次自定义有差异
          <button v-if="auth.has('reduce.policy.edit')" class="btn sm" style="margin-left:auto" @click="syncReduce">一键同步为项目最新</button>
        </div>
        <div v-if="tiersPermDenied" class="alert warn" style="margin-top:8px">无减免策略查看权限</div>
        <table v-else style="margin-top:8px">
          <thead><tr><th>折扣</th><th>封顶</th><th>决定权</th><th style="text-align:center">免违约金</th></tr></thead>
          <tbody>
            <tr v-for="(t,ti) in tiers" :key="ti">
              <td>{{ t.discount }}</td>
              <td class="num">{{ yuan(t.capCents) }}</td>
              <td :title="t.decide">{{ reduceDecideLabel(t.decide) }}</td>
              <td style="text-align:center">{{ t.waivePenalty ? '✓' : '—' }}</td>
            </tr>
            <tr v-if="!tiers.length"><td colspan="4" class="empty-cell">暂无减免档位</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 协调员 -->
      <div class="card">
        <div class="sec-title">
          协调员（本批次）
          <button v-if="auth.has('batch.import') || auth.has('proj.edit')" class="btn sm" style="margin-left:auto" @click="openCoord">+ 维护协调员</button>
        </div>
        <div v-if="b.coordinators && b.coordinators.length" class="coord-tags">
          <span v-for="c in b.coordinators" :key="c.id" class="tag pri" style="margin-right:6px;display:inline-flex;align-items:center;gap:4px">{{ c.name || c.id }}</span>
        </div>
        <div v-else class="note">暂未关联协调员</div>
      </div>
    </template>

    <!-- 案件明细 Tab -->
    <div v-if="batchTab === 'cases'" class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>案件明细 — {{ b.code }}</div>
        <div class="ops">
          <span class="note" style="margin:0">{{ cases.length }} 条</span>
        </div>
      </div>
      <table>
        <thead><tr><th>业主</th><th>房号</th><th style="width:100px">应收</th><th>欠费周期</th><th>联系方式</th><th style="width:90px">状态</th><th style="width:80px">池</th></tr></thead>
        <tbody>
          <tr v-for="c in cases" :key="c.id" class="row-click" @click="openCase(c)">
            <td>{{ c.ownerName || '—' }}</td>
            <td>{{ c.room || '—' }}</td>
            <td class="num">{{ yuan(c.dueCents) }}</td>
            <td>{{ c.arrearPeriod || '—' }}</td>
            <td>{{ c.phone || '—' }}</td>
            <td><span class="tag" :class="statusTag(c.status)">{{ caseStatusLabel(c.status) }}</span></td>
            <td :title="c.pool">{{ poolLabel(c.pool) }}</td>
          </tr>
          <tr v-if="!cases.length"><td colspan="7" class="empty-cell">暂无案件</td></tr>
        </tbody>
      </table>
    </div>

    <!-- BC-01 协调员维护对话框(复用 CoordinatorPicker) -->
    <CoordinatorPicker v-model="coordDlg" :selected="b.coordinators ?? []" title="维护批次协调员（PUT /batches/{id}/coordinators）" @submit="saveCoordinators" />

    <!-- BC-02 减免覆盖编辑对话框 -->
    <el-dialog v-model="reduceDlg" title="批次自定义减免（PUT /batches/{id}/reduce-tiers · 全量覆盖）" width="760px">
      <el-table :data="reduceRows" border size="small">
        <el-table-column label="折扣"><template #default="{row}"><el-input v-model="row.discount" size="small" placeholder="如 9折" /></template></el-table-column>
        <el-table-column label="封顶(元)" width="140"><template #default="{row}"><el-input-number v-model="row.capYuan" size="small" :min="0" :controls="false" style="width:110px" /></template></el-table-column>
        <el-table-column label="免违约金" width="100"><template #default="{row}"><el-switch v-model="row.waivePenalty" /></template></el-table-column>
        <el-table-column label="决定权" width="180"><template #default="{row}">
          <el-select v-model="row.decide" size="small">
            <el-option label="催收员自决" value="COLLECTOR_SELF" />
            <el-option label="线下内部流程" value="OFFLINE_INTERNAL" />
            <el-option label="物业负责人审批" value="PL_APPROVE" />
          </el-select>
        </template></el-table-column>
        <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" :disabled="reduceRows.length<=1" @click="reduceRows.splice($index,1)">×</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" text type="primary" style="margin-top:6px" @click="reduceRows.push(emptyTier())">+ 添加档</el-button>
      <div style="margin-top:6px;color:#909399;font-size:12px">封顶按元录入、提交按分存（capCents）。空折扣行会被忽略；全部清空请用「清除自定义·恢复继承」。</div>
      <template #footer><el-button @click="reduceDlg=false">取消</el-button><el-button type="primary" @click="saveReduce">保存覆盖</el-button></template>
    </el-dialog>

    <!-- BC-03 手册采纳对话框 -->
    <DsDrawer v-model="pbDlg" title="采纳批次作战手册" :width="560">
      <el-form label-width="70px">
        <el-form-item label="版本"><el-input v-model="pbForm.version" placeholder="如 v1.1" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="pbForm.content" type="textarea" :rows="8" placeholder="批次自定义作战手册正文" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="pbDlg=false">取消</el-button><el-button type="primary" @click="adoptPlaybook">采纳发布</el-button></template>
    </DsDrawer>
  </div>
</template>

<style scoped>
.batch-detail .sec-title { justify-content: flex-start; flex-wrap: wrap; }
.batch-detail .sec-title .btn.txt { margin-left: 4px; }
.coord-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.pb-content { white-space: pre-wrap; max-height: 160px; overflow: auto; }
.empty-cell { text-align: center; color: var(--sec); padding: 18px 14px; }
</style>
