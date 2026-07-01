<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import ProjectEditDialog from '../components/ProjectEditDialog.vue'
import { statusLabel, reduceDecideLabel } from '../constants/enums'

// GET /projects（契约客户端）。资金双线：平台/物业见 commInRate，服务商视角字段级无。
// 列表内置搜索筛选(q/status)由服务端支持；查看档案=内联展开项目全量详情；查看批次→案件列表；编辑→ProjectEditDialog。
const router = useRouter()
const auth = useAuth()
const { showCommInRate, ratePct, isProperty } = useRoleFields()

// ── 列表状态 ──
const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

// ── 筛选 ──
const filter = ref({ q: '', status: '' })

async function load() {
  loading.value = true
  const query: Record<string, any> = { page: page.value, size: size.value }
  if (filter.value.q) query.q = filter.value.q
  if (filter.value.status) query.status = filter.value.status
  const { data, error } = await api.GET('/projects', { params: { query } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

function search() { page.value = 1; load() }
function resetFilter() { filter.value = { q: '', status: '' }; search() }

// 防抖：名称输入 400ms 后自动搜
let debounceTimer: ReturnType<typeof setTimeout> | null = null
watch(() => filter.value.q, () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(search, 400)
})
watch(() => filter.value.status, () => search())

// ── 内联项目档案（查看档案→展开）──
const selProj = ref<any>(null)
const selProjLoading = ref(false)

async function viewProfile(row: any) {
  selProjLoading.value = true
  const { data, error } = await api.GET('/projects/{id}', { params: { path: { id: row.id || row } } })
  selProjLoading.value = false
  if (error || !data) { ElMessage.error('加载项目详情失败'); return }
  selProj.value = data as any
  // 初始化减免阶梯编辑模型（capCents→capYuan 便于输入）
  reduceRows.value = ((data as any).reduceTiers ?? []).map((t: any) => ({
    discount: t.discount ?? '',
    capYuan: t.capCents != null ? t.capCents / 100 : (null as number | null),
    waivePenalty: !!t.waivePenalty,
    decide: t.decide ?? 'COLLECTOR_SELF',
  }))
  reduceDirty.value = false
}

function viewBatches(row: any) {
  // 导航到案件列表（批次优先），by project
  router.push({ path: '/cases', query: { projectId: row.id } })
}

// ── 编辑 ──
const editDlg = ref(false)
const editProject = ref<any>(null)
function openEdit(row: any) { editProject.value = row; editDlg.value = true }
function onProjectSaved() {
  load()
  if (selProj.value) {
    // 刷新内联档案
    viewProfile({ id: selProj.value.id ?? selProj.value })
  }
}

// ── 内联减免阶梯维护（PL 角色） ──
const reduceRows = ref<{ discount: string; capYuan: number | null; waivePenalty: boolean; decide: string }[]>([])
const reduceDirty = ref(false)
function markDirty() { reduceDirty.value = true }
function addTier() {
  reduceRows.value.push({ discount: '', capYuan: null, waivePenalty: false, decide: 'COLLECTOR_SELF' })
  reduceDirty.value = true
}
function removeTier(i: number) { reduceRows.value.splice(i, 1); reduceDirty.value = true }
async function saveReduce() {
  const pid = selProj.value?.id
  if (!pid) return
  const payload = reduceRows.value
    .filter(r => r.discount && r.discount.trim())
    .map(r => ({
      discount: r.discount.trim(),
      capCents: r.capYuan != null ? Math.round(r.capYuan * 100) : null,
      waivePenalty: r.waivePenalty,
      decide: r.decide as any,
    }))
  const { error } = await api.PUT('/projects/{id}/reduce-tiers', { params: { path: { id: pid } }, body: payload as any })
  if (error) { ElMessage.error('保存减免政策失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('项目级减免政策已保存（' + payload.length + ' 档阶梯，批次级可覆盖）')
  reduceDirty.value = false
  // 回读刷新
  await viewProfile({ id: pid })
}
function backToList() { selProj.value = null }

// ── 纯展示辅助 ──
const statusTag = (s?: string) => (s === '启用' || s === 'ACTIVE' || s === 'ENABLED' ? 'suc' : 'inf')
const DECIDE_TAG: Record<string, string> = { COLLECTOR_SELF: 'suc', OFFLINE_INTERNAL: 'war', PL_APPROVE: 'pri' }
const decideTag = (d?: string) => DECIDE_TAG[d ?? ''] ?? 'inf'
const yuan = (c?: number | null) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const feeStdText = (p: any) => {
  if (p?.feeRows?.length) return p.feeRows.map((r: any) => (r.biz ? r.biz + ' ' : '') + (r.std || '')).join('；')
  return p?.feeStd || '—'
}

onMounted(load)
</script>

<template>
  <!-- ═══════════════ 列 表 视 图 ═══════════════ -->
  <div v-if="!selProj" class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>项目（小区）</div>
      <div class="ops">
        <span class="note" style="margin:0">GET /projects · 共 {{ total }} · q/status 服务端筛选</span>
        <button v-if="auth.has('proj.edit')" class="btn sm" @click="editProject = null; editDlg = true">+ 新建项目</button>
      </div>
    </div>

    <!-- 筛选工具栏 -->
    <div class="toolbar">
      <input class="inp" v-model="filter.q" placeholder="项目名称" aria-label="项目名称">
      <select class="inp" v-model="filter.status" aria-label="状态筛选">
        <option value="">状态：全部</option>
        <option value="ACTIVE">启用</option>
        <option value="INACTIVE">停用</option>
      </select>
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>项目名称</th>
          <th>物业</th>
          <th>区域</th>
          <!-- 资金双线：收佣比例整列仅平台/物业视角渲染(H-03) -->
          <th v-if="showCommInRate">收佣比例</th>
          <th>状态</th>
          <th style="width:210px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.name || '—' }}</td>
          <td>{{ row.org || '—' }}</td>
          <td>{{ row.area || '—' }}</td>
          <td v-if="showCommInRate" class="num">{{ ratePct(row.commInRate) }}</td>
          <td><span class="tag" :class="statusTag(row.status)" :title="row.status">{{ statusLabel(row.status) }}</span></td>
          <td>
            <a class="btn txt" @click="viewProfile(row)">查看档案</a>
            <a class="btn txt" @click="viewBatches(row)">查看批次</a>
            <a v-if="auth.has('proj.edit')" class="btn txt" @click="openEdit(row)">编辑</a>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td :colspan="showCommInRate ? 6 : 5" style="text-align:center;color:var(--sec);padding:32px 0">暂无项目</td>
        </tr>
      </tbody>
    </table>

    <!-- 分页 -->
    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="page > 1 && (page--, load())">‹</div>
      <div class="pg on">{{ page }}</div>
      <div class="pg" @click="page * size < total && (page++, load())">›</div>
    </div>

    <!-- 编辑对话框（共用） -->
    <ProjectEditDialog v-model="editDlg" :project="editProject" @saved="onProjectSaved" />
  </div>

  <!-- ═══════════════ 内联项目档案 ═══════════════ -->
  <div v-if="selProj" v-loading="selProjLoading" class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>{{ selProj.name }} · 项目档案</div>
      <div class="ops">
        <button class="btn df sm" @click="backToList">返回列表</button>
      </div>
    </div>

    <!-- 项目信息 -->
    <div class="sec-title" style="margin-top:0">项目信息（按物业创建）</div>
    <div class="desc">
      <div class="r"><div class="k">物业公司</div><div class="v">{{ selProj.propCompany || '—' }}</div></div>
      <div class="r"><div class="k">物业合同</div><div class="v">{{ selProj.contractType || '—' }}</div></div>
      <div class="r"><div class="k">收费标准</div><div class="v">{{ feeStdText(selProj) }}（{{ selProj.feeCycle || '—' }}，文字描述非算金额源）</div></div>
      <div class="r"><div class="k">违约金</div><div class="v">{{ selProj.penalty || '—' }}</div></div>
      <div class="r"><div class="k">收款信息</div><div class="v">{{ selProj.payInfo || '—' }}</div></div>
      <div class="r"><div class="k">应收总额 / 已收总额</div><div class="v">{{ yuan(selProj.dueTotalCents) }} / {{ yuan(selProj.repayTotalCents) }}</div></div>
      <div v-if="showCommInRate" class="r"><div class="k">收佣比例</div><div class="v num">{{ ratePct(selProj.commInRate) }}</div></div>
      <div class="r"><div class="k">状态</div><div class="v" :title="selProj.status">{{ statusLabel(selProj.status) }}</div></div>
    </div>

    <!-- 减免政策维护（PL 角色可内联编辑；其他角色只读） -->
    <template v-if="isProperty">
      <div class="sec-title">
        减免政策维护（项目级·阶梯）
        <span style="font-size:12px;color:var(--sec);font-weight:400;margin-left:8px">批次级可在批次详情覆盖</span>
      </div>
      <div class="alert info" style="margin-top:0">
        阶梯折扣 + 是否减免违约金 + 决定权：<b>催收员自决</b>档系统直接生效；<b>线下内部流程</b>档由物业内部线下处理、系统仅留痕（不在系统审批）。批次级留空继承此政策。
      </div>
      <table>
        <thead>
          <tr>
            <th>折扣</th>
            <th>减免上限</th>
            <th style="text-align:center">含违约金减免</th>
            <th>决定权</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(t, i) in reduceRows" :key="i">
            <td><input class="inp" v-model="t.discount" placeholder="如 9折" @input="markDirty" style="min-width:80px"></td>
            <td><input class="inp" v-model.number="t.capYuan" placeholder="¥上限（空=不限）" @input="markDirty" style="min-width:120px" type="number"></td>
            <td style="text-align:center"><input type="checkbox" v-model="t.waivePenalty" @change="markDirty" aria-label="含违约金减免"></td>
            <td>
              <select class="inp" v-model="t.decide" @change="markDirty">
                <option value="COLLECTOR_SELF">催收员自决</option>
                <option value="OFFLINE_INTERNAL">线下内部流程</option>
                <option value="PL_APPROVE">物业负责人审批</option>
              </select>
            </td>
            <td><a class="btn txt dgc" @click="removeTier(i)">删</a></td>
          </tr>
          <tr v-if="!reduceRows.length">
            <td colspan="5" class="note" style="text-align:center">暂无阶梯，点击下方「+ 增加阶梯」</td>
          </tr>
        </tbody>
      </table>
      <div style="margin:8px 0">
        <button class="btn df sm" @click="addTier">+ 增加阶梯</button>
        <button class="btn sm" style="margin-left:8px" @click="saveReduce" :disabled="!reduceDirty">保存减免政策</button>
        <span class="note" style="margin-left:8px">批次级留空则继承此政策</span>
      </div>
    </template>

    <template v-if="!isProperty">
      <div class="sec-title">
        减免政策
        <span class="note" style="margin:0 0 0 4px;font-weight:400">{{ reduceRows.length ? reduceRows.length + ' 档阶梯' : '—' }}</span>
      </div>
      <table>
        <thead><tr><th>折扣</th><th>封顶</th><th>决定权</th><th>免违约金</th></tr></thead>
        <tbody>
          <tr v-for="(t, i) in reduceRows" :key="i">
            <td>{{ t.discount || '—' }}</td>
            <td class="num">{{ t.capYuan != null ? '¥' + t.capYuan.toLocaleString('zh-CN') : '不限' }}</td>
            <td><span class="tag" :class="decideTag(t.decide)">{{ reduceDecideLabel(t.decide) }}</span></td>
            <td>{{ t.waivePenalty ? '是' : '否' }}</td>
          </tr>
          <tr v-if="!reduceRows.length">
            <td colspan="4" class="note" style="text-align:center">尚无减免阶梯。</td>
          </tr>
        </tbody>
      </table>
    </template>

    <!-- 编辑对话框（内联档案内亦可编辑） -->
    <div style="margin-top:16px">
      <button v-if="auth.has('proj.edit')" class="btn sm" @click="openEdit(selProj)">编辑档案</button>
    </div>
  </div>
</template>
