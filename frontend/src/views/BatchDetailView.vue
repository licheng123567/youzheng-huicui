<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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

// 案件筛选（案件明细 tab）
const caseFilter = ref({ q: '', status: '', amtMin: null as number | null, amtMax: null as number | null, owner: '' })
function resetCaseFilter() { caseFilter.value = { q: '', status: '', amtMin: null, amtMax: null, owner: '' } }
const filteredCases = computed(() => {
  let list = cases.value
  const f = caseFilter.value
  if (f.q) { const q = f.q.toLowerCase(); list = list.filter((c: any) => (c.ownerName || '').includes(q) || (c.room || '').includes(q) || (c.phone || '').includes(q)) }
  if (f.status) list = list.filter((c: any) => c.status === f.status)
  if (f.amtMin != null) list = list.filter((c: any) => (c.dueCents || 0) >= f.amtMin! * 100)
  if (f.amtMax != null) list = list.filter((c: any) => (c.dueCents || 0) <= f.amtMax! * 100)
  if (f.owner) list = list.filter((c: any) => (c.collectorName || '').includes(f.owner))
  return list
})

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

// ── 手动添加案件 ──
const manualDlg = ref(false)
const mForm = ref({ acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, periodFrom: '', periodTo: '', idCard: '', addr: '' })
const mSaving = ref(false)
const mPhoneErr = ref(''); const mIdCardErr = ref('')

const arrearMonths = computed(() => {
  if (!mForm.value.periodFrom || !mForm.value.periodTo) return null
  const from = new Date(mForm.value.periodFrom)
  const to = new Date(mForm.value.periodTo)
  if (isNaN(from.getTime()) || isNaN(to.getTime()) || from >= to) return null
  const diffDays = Math.round((to.getTime() - from.getTime()) / 86400000)
  const months = Math.floor(diffDays / 30)
  const remDays = diffDays % 30
  return months > 0 ? (remDays > 0 ? `${months}个月${remDays}天` : `${months}个月`) : `${diffDays}天`
})

function validatePhone(v: string) { mPhoneErr.value = v && !/^1\d{10}$/.test(v) ? '手机号须为 11 位数字' : '' }
function validateIdCard(v: string) { mIdCardErr.value = v && !/^\d{17}[\dXx]$/.test(v) ? '身份证号须为 18 位' : '' }

function openManualAdd() {
  mForm.value = { acctNo: '', ownerName: '', phone: '', room: '', dueYuan: 0, periodFrom: '', periodTo: '', idCard: '', addr: '' }
  mPhoneErr.value = ''; mIdCardErr.value = ''
  manualDlg.value = true
}

async function submitManual() {
  const f = mForm.value
  if (!f.acctNo || !f.ownerName || !f.phone) { ElMessage.warning('户号/姓名/手机为必填'); return }
  if (mPhoneErr.value) { ElMessage.warning('手机号格式不正确'); return }
  if (mIdCardErr.value) { ElMessage.warning('身份证号格式不正确'); return }
  if (f.dueYuan <= 0) { ElMessage.warning('应收金额须 > 0'); return }
  const period = f.periodFrom && f.periodTo ? `${f.periodFrom}~${f.periodTo}` : ''
  const body: any = { acctNo: f.acctNo, ownerName: f.ownerName, phone: f.phone, room: f.room, dueCents: Math.round(f.dueYuan * 100), arrearPeriod: period }
  if (f.idCard || f.addr) body.litigation = { ...(f.idCard ? { idCard: f.idCard } : {}), ...(f.addr ? { addr: f.addr } : {}) }
  mSaving.value = true
  const { error } = await api.POST('/batches/{id}/cases', { params: { path: { id: bid } }, body } as any)
  mSaving.value = false
  if (error) { ElMessage.error('添加失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已添加案件'); manualDlg.value = false
  cases.value = ((await api.GET('/cases', { params: { query: { batchId: bid, page: 1, size: 100 } } as any })).data as any)?.items ?? []
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
        <div class="sec-title">作战手册（批次级） <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">继承项目 或 自定义覆盖（手册随批次走）</span></div>
        <template v-if="auth.has('playbook.adopt')">
          <div style="display:flex;gap:16px;margin-bottom:8px;font-size:13px">
            <label style="display:flex;align-items:center;gap:5px;cursor:pointer"><input type="radio" value="inherit" :checked="playbookSource !== 'CUSTOM'" @change="playbookSource === 'CUSTOM' && restorePlaybook()"> 继承项目</label>
            <label style="display:flex;align-items:center;gap:5px;cursor:pointer"><input type="radio" value="custom" :checked="playbookSource === 'CUSTOM'" @change="playbookSource !== 'CUSTOM' && openPlaybook()"> 自定义覆盖</label>
          </div>
          <template v-if="playbookSource === 'CUSTOM' && playbook">
            <div class="pb-content" style="margin-bottom:8px;white-space:pre-wrap;max-height:160px;overflow:auto;background:#f9fafb;border:1px solid var(--bd);padding:10px;border-radius:4px;font-size:13px">{{ playbook.content || '（尚无内容）' }}</div>
            <button class="btn txt" @click="openPlaybook">编辑手册</button>
            <div v-if="b.playbookDrift" class="alert warn" style="margin-top:8px">⚠ 项目级作战手册已更新，当前批次自定义有差异。<button class="btn sm" style="margin-left:8px" @click="syncPlaybook">同步为项目最新</button></div>
          </template>
          <div v-else class="note">继承项目级作战手册（项目修改后自动跟随）。</div>
        </template>
        <div v-else class="note">只读调阅。当前：{{ playbookSource === 'CUSTOM' ? '本批次自定义覆盖' : '继承项目级' }}</div>
      </div>

      <!-- 减免政策 -->
      <div class="card">
        <div class="sec-title">减免政策（批次级） <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">继承项目 或 自定义覆盖阶梯（批次级优先）</span></div>
        <template v-if="!tiersPermDenied && auth.has('reduce.policy.edit')">
          <div style="display:flex;gap:16px;margin-bottom:8px;font-size:13px">
            <label style="display:flex;align-items:center;gap:5px;cursor:pointer"><input type="radio" value="inherit" :checked="tiersSource !== 'CUSTOM'" @change="tiersSource === 'CUSTOM' && clearReduce()"> 继承项目</label>
            <label style="display:flex;align-items:center;gap:5px;cursor:pointer"><input type="radio" value="custom" :checked="tiersSource === 'CUSTOM'" @change="tiersSource !== 'CUSTOM' && openReduce()"> 自定义覆盖</label>
          </div>
          <table v-if="tiersSource === 'CUSTOM'">
            <thead><tr><th>折扣</th><th>封顶</th><th style="text-align:center">免违约金</th><th>决定权</th></tr></thead>
            <tbody>
              <tr v-for="(t,ti) in tiers" :key="ti">
                <td>{{ t.discount }}</td><td class="num">{{ yuan(t.capCents) }}</td>
                <td style="text-align:center">{{ t.waivePenalty ? '✓' : '—' }}</td>
                <td :title="t.decide">{{ reduceDecideLabel(t.decide) }}</td>
              </tr>
              <tr v-if="!tiers.length"><td colspan="4" class="empty-cell">暂无阶梯</td></tr>
            </tbody>
          </table>
          <div v-else class="note">继承项目级减免政策（项目修改后自动跟随）。</div>
          <div v-if="tiersSource==='CUSTOM'" style="margin-top:6px">
            <button class="btn txt" @click="openReduce">编辑阶梯</button>
            <button class="btn txt" @click="clearReduce">恢复继承</button>
          </div>
          <div v-if="b.reduceDrift" class="alert warn" style="margin-top:8px">⚠ 项目级减免已更新，当前批次自定义有差异。<button class="btn sm" style="margin-left:8px" @click="syncReduce">同步为项目最新</button></div>
        </template>
        <div v-else class="note">{{ tiersPermDenied ? '无减免策略查看权限' : (tiersSource === 'CUSTOM' ? '本批次自定义减免阶梯' : '继承项目级减免政策') }}</div>
      </div>

      <!-- 起诉要素字段集 -->
      <div class="card">
        <div class="sec-title">起诉要素字段集 <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">只读示意 · 随导入采集 · 为起诉状备数据</span></div>
        <div class="alert info" style="margin-top:0;margin-bottom:10px">以下字段随案件导入时采集，供生成起诉状使用。当前仅作只读展示。</div>
        <div style="display:flex;flex-wrap:wrap;gap:6px">
          <span class="tag inf" style="font-size:11px">业主姓名</span><span class="tag inf" style="font-size:11px">房号</span><span class="tag inf" style="font-size:11px">欠费金额</span><span class="tag inf" style="font-size:11px">欠费周期</span><span class="tag inf" style="font-size:11px">身份证号</span><span class="tag inf" style="font-size:11px">通讯地址</span><span class="tag inf" style="font-size:11px">联系电话</span><span class="tag inf" style="font-size:11px">物业公司</span><span class="tag inf" style="font-size:11px">统一信用代码</span>
        </div>
      </div>

      <!-- 协调员 -->
      <div class="card">
        <div class="sec-title" style="display:flex;align-items:center">
          协调员（本批次）
          <button v-if="auth.has('proj.edit')" class="btn sm" style="margin-left:auto" @click="openCoord">+ 添加协调员</button>
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
          <span class="note" style="margin:0">{{ filteredCases.length }}/{{ cases.length }} 条</span>
          <button v-if="auth.has('batch.import') || auth.has('proj.edit')" class="btn sm" style="margin-left:8px" @click="openManualAdd">+ 手动添加案件</button>
        </div>
      </div>
      <!-- 筛选栏 -->
      <div class="search" style="margin-bottom:10px">
        <div class="fi"><span>搜索</span><input class="inp" v-model="caseFilter.q" placeholder="业主 / 房号 / 电话" style="min-width:140px" @keyup.enter="()=>{}" /></div>
        <div class="fi"><span>状态</span>
          <select class="inp" v-model="caseFilter.status">
            <option value="">全部状态</option>
            <option value="PENDING_DISPATCH">待派单</option><option value="IN_PROGRESS">催收中</option>
            <option value="SETTLED">已结清</option><option value="WITHDRAWN">已撤回</option><option value="BAD_DEBT">坏账</option>
          </select>
        </div>
        <div class="fi"><span>金额≥</span><input class="inp" type="number" v-model.number="caseFilter.amtMin" style="min-width:80px" placeholder="0" /></div>
        <div class="fi"><span>金额≤</span><input class="inp" type="number" v-model.number="caseFilter.amtMax" style="min-width:80px" placeholder="∞" /></div>
        <div class="fi"><span>催收员</span><input class="inp" v-model="caseFilter.owner" placeholder="归属催收员" style="min-width:100px" /></div>
        <div class="fi"><button class="btn sm" @click="resetCaseFilter">重置</button></div>
      </div>
      <table>
        <thead><tr><th>业主</th><th>房号</th><th style="width:100px">应收</th><th style="width:90px">状态</th><th>归属催收员</th><th>联系方式</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="c in filteredCases" :key="c.id" class="row-click" @click="openCase(c)">
            <td>{{ c.ownerName || '—' }}</td>
            <td>{{ c.room || '—' }}</td>
            <td class="num">{{ yuan(c.dueCents) }}</td>
            <td><span class="tag" :class="statusTag(c.status)">{{ caseStatusLabel(c.status) }}</span></td>
            <td>{{ c.collectorName || '—' }}</td>
            <td>{{ c.phone || '—' }}</td>
            <td @click.stop><a class="btn txt" @click="openCase(c)">查看详情 ›</a></td>
          </tr>
          <tr v-if="!filteredCases.length"><td colspan="7" class="empty-cell">{{ cases.length ? '无匹配案件' : '暂无案件' }}</td></tr>
        </tbody>
      </table>
    </div>

    <!-- BC-01 协调员维护对话框(复用 CoordinatorPicker) -->
    <CoordinatorPicker v-model="coordDlg" :selected="b.coordinators ?? []" title="维护批次协调员（PUT /batches/{id}/coordinators）" @submit="saveCoordinators" />

    <!-- 手动添加案件对话 -->
    <DsDrawer v-model="manualDlg" title="手动添加案件" :width="520">
      <el-form label-width="90px">
        <el-form-item label="户号" required><el-input v-model="mForm.acctNo" placeholder="如 3-201" /></el-form-item>
        <el-form-item label="业主姓名" required><el-input v-model="mForm.ownerName" placeholder="如 张三" /></el-form-item>
        <el-form-item label="手机号" required :error="mPhoneErr">
          <el-input v-model="mForm.phone" placeholder="11 位手机号" @blur="validatePhone(mForm.phone)" />
        </el-form-item>
        <el-form-item label="房号"><el-input v-model="mForm.room" placeholder="如 3-201" /></el-form-item>
        <el-form-item label="应收金额(元)" required><el-input-number v-model="mForm.dueYuan" :min="0" :step="100" style="width:100%" /></el-form-item>

        <el-divider content-position="left">欠费期间（自动计算月数）</el-divider>
        <div style="display:grid;grid-template-columns:1fr auto 1fr;gap:8px;align-items:center">
          <el-date-picker v-model="mForm.periodFrom" type="month" placeholder="起始月份" value-format="YYYY-MM" style="width:100%" />
          <span style="color:var(--sec)">~</span>
          <el-date-picker v-model="mForm.periodTo" type="month" placeholder="截止月份" value-format="YYYY-MM" style="width:100%" />
        </div>
        <div v-if="arrearMonths" style="margin-top:8px;color:var(--primary);font-size:14px">
          欠费时长：<b>{{ arrearMonths }}</b>
        </div>

        <el-divider content-position="left">诉讼要素（选填·可后补）</el-divider>
        <el-form-item label="身份证号" :error="mIdCardErr">
          <el-input v-model="mForm.idCard" placeholder="18 位身份证号" @blur="validateIdCard(mForm.idCard)" />
        </el-form-item>
        <el-form-item label="通讯地址"><el-input v-model="mForm.addr" placeholder="如 成都市武侯区XX路XX号" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDlg = false">取消</el-button>
        <el-button type="primary" :loading="mSaving" @click="submitManual">添加到批次</el-button>
      </template>
    </DsDrawer>

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
