<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import ProjectEditDialog from '../components/ProjectEditDialog.vue'
import CoordinatorPicker from '../components/CoordinatorPicker.vue'
import { statusLabel, reduceDecideLabel } from '../constants/enums'

// GET /projects/{id} → oneOf(Project|ProjectForProvider)；viewRole 判别。
// + 档案编辑(H-07/US-M2-01) + 协调员维护(US-M2-02) + 减免阶梯(BR-M2-18a) + 作战手册采纳(US-M5-07)。
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const { showCommInRate, ratePct } = useRoleFields()
const pid = String(route.params.id)
const p = ref<any>(null)
const playbook = ref<any>(null)
const yuan = (c?: number | null) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// ===== 纯展示辅助（仅 UI 表现层，不参与数据流）=====
// 决定权 → ds-admin .tag 配色
const DECIDE_TAG: Record<string, string> = { COLLECTOR_SELF: 'suc', OFFLINE_INTERNAL: 'war', PL_APPROVE: 'pri' }
const decideTag = (d?: string) => DECIDE_TAG[d ?? ''] ?? 'inf'

async function load() {
  const { data, error } = await api.GET('/projects/{id}', { params: { path: { id: pid } } })
  if (error || !data) { ElMessage.error('加载失败'); return }
  p.value = data
  playbook.value = (await api.GET('/projects/{id}/playbook', { params: { path: { id: pid } } })).data
}

// ── H-07 档案编辑(PUT /projects/{id}) ──
const editDlg = ref(false)
function openEdit() { editDlg.value = true }
function onSaved() { load() }

// ── US-M2-02 协调员维护(PUT /projects/{id}/coordinators · proj.edit) ──
const coordDlg = ref(false)
function openCoord() { coordDlg.value = true }
async function saveCoordinators(coordinatorIds: string[]) {
  const { error } = await api.PUT('/projects/{id}/coordinators', { params: { path: { id: pid } }, body: { coordinatorIds } })
  if (error) { ElMessage.error('保存协调员失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已更新项目协调员'); coordDlg.value = false; load()
}

// ── BR-M2-18a 减免阶梯维护(PUT /projects/{id}/reduce-tiers · reduce.policy.edit·裸数组) ──
const reduceDlg = ref(false)
// 编辑模型：capYuan 元展示、提交 ×100 存 capCents
const reduceRows = ref<{ discount: string; capYuan: number | null; waivePenalty: boolean; decide: string }[]>([])
function emptyTier() { return { discount: '', capYuan: null as number | null, waivePenalty: false, decide: 'COLLECTOR_SELF' } }
function openReduce() {
  reduceRows.value = (p.value?.reduceTiers ?? []).map((t: any) => ({
    discount: t.discount ?? '',
    capYuan: t.capCents != null ? t.capCents / 100 : null,
    waivePenalty: !!t.waivePenalty,
    decide: t.decide ?? 'COLLECTOR_SELF',
  }))
  if (!reduceRows.value.length) reduceRows.value = [emptyTier()]
  reduceDlg.value = true
}
async function saveReduce() {
  // 裸数组 ReduceTier[]；空数组=清空。capCents 元→分。
  const payload = reduceRows.value
    .filter((r) => r.discount && r.discount.trim())
    .map((r) => ({
      discount: r.discount,
      capCents: r.capYuan != null ? Math.round(r.capYuan * 100) : null,
      waivePenalty: r.waivePenalty,
      decide: r.decide as any,
    }))
  const { error } = await api.PUT('/projects/{id}/reduce-tiers', { params: { path: { id: pid } }, body: payload })
  if (error) { ElMessage.error('保存减免规则失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已保存减免阶梯'); reduceDlg.value = false; load()
}
async function clearReduce() {
  try {
    await ElMessageBox.confirm('清空全部减免阶梯？', '清空减免规则', { type: 'warning' })
  } catch { return }
  const { error } = await api.PUT('/projects/{id}/reduce-tiers', { params: { path: { id: pid } }, body: [] })
  if (error) { ElMessage.error('清空失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已清空减免阶梯'); load()
}

// ── US-M5-07 作战手册采纳(POST /projects/{id}/playbook · playbook.adopt) ──
const dlg = ref(false); const form = ref<any>({ version: '', content: '' })
function openAdopt() { form.value = { version: playbook.value?.version ?? 'v1.0', content: playbook.value?.content ?? '' }; dlg.value = true }
async function adopt() {
  const { error } = await api.POST('/projects/{id}/playbook', { params: { path: { id: pid } }, body: { version: form.value.version, content: form.value.content } as any })
  if (error) { ElMessage.error('采纳失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已采纳/发布作战手册'); dlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <div v-if="p" class="card">
    <div class="card-h">
      <div class="t">
        <span class="bar"></span>
        <a class="btn txt" @click="router.back()">← 返回</a>
        项目详情：{{ p.name }}
        <span class="tag inf" style="font-weight:400">视角 {{ p.viewRole }}</span>
      </div>
      <div class="ops">
        <button v-if="auth.has('proj.edit')" class="btn sm" @click="openEdit">编辑档案</button>
      </div>
    </div>

    <!-- 项目概览 -->
    <div class="sec-title" style="margin-top:0">项目信息</div>
    <div class="desc">
      <div class="r"><div class="k">ID</div><div class="v">{{ p.id }}</div></div>
      <div class="r"><div class="k">区域</div><div class="v">{{ p.area }}</div></div>
      <div class="r"><div class="k">物业公司</div><div class="v">{{ p.propCompany ?? '—' }}</div></div>
      <div class="r"><div class="k">合同类型</div><div class="v">{{ p.contractType ?? '—' }}</div></div>
      <!-- 资金双线：收佣比例整项仅平台/物业渲染，服务商视角字段级无→整项不出(H-03) -->
      <div v-if="showCommInRate" class="r"><div class="k">收佣比例</div><div class="v num">{{ ratePct(p.commInRate) }}</div></div>
      <div class="r"><div class="k">状态</div><div class="v" :title="p.status">{{ statusLabel(p.status) }}</div></div>
    </div>

    <!-- US-M2-02 关联协调员 -->
    <div class="sec-title">
      关联协调员
      <span class="note" style="margin:0 0 0 4px;font-weight:400">PC↔项目 多对多 BR-M2-13</span>
      <button v-if="auth.has('proj.edit')" class="btn txt" style="margin-left:auto" @click="openCoord">维护协调员</button>
    </div>
    <div v-if="p.coordinators && p.coordinators.length">
      <span v-for="c in p.coordinators" :key="c.id" class="tag pri" style="margin-right:6px">{{ c.name || c.id }}</span>
    </div>
    <div v-else class="note">尚未关联协调员。</div>

    <!-- BR-M2-18a 减免阶梯 -->
    <div class="sec-title">
      减免阶梯
      <span class="note" style="margin:0 0 0 4px;font-weight:400">BR-M2-18a 阶梯+决定权</span>
      <span style="margin-left:auto">
        <button v-if="auth.has('reduce.policy.edit')" class="btn txt" @click="openReduce">维护减免规则</button>
        <button v-if="auth.has('reduce.policy.edit') && p.reduceTiers && p.reduceTiers.length" class="btn txt dgc" @click="clearReduce">清空</button>
      </span>
    </div>
    <table>
      <thead><tr><th>折扣</th><th>封顶</th><th>决定权</th><th>免违约金</th></tr></thead>
      <tbody>
        <tr v-for="(t,i) in (p.reduceTiers ?? [])" :key="i">
          <td>{{ t.discount }}</td>
          <td class="num">{{ yuan(t.capCents) }}</td>
          <td><span class="tag" :class="decideTag(t.decide)" :title="t.decide">{{ reduceDecideLabel(t.decide) }}</span></td>
          <td>{{ t.waivePenalty ? '是' : '否' }}</td>
        </tr>
        <tr v-if="!(p.reduceTiers && p.reduceTiers.length)"><td colspan="4" class="note" style="text-align:center">尚无减免阶梯。</td></tr>
      </tbody>
    </table>

    <!-- US-M5-07 作战手册 -->
    <div class="sec-title">
      作战手册
      <span class="note" style="margin:0 0 0 4px;font-weight:400">AI 产草稿→物业采纳才发布 US-M5-07</span>
      <button v-if="auth.has('playbook.adopt')" class="btn txt" style="margin-left:auto" @click="openAdopt">采纳/编辑</button>
    </div>
    <template v-if="playbook">
      <div class="desc">
        <div class="r"><div class="k">版本</div><div class="v">{{ playbook.version ?? '—' }}</div></div>
        <div class="r"><div class="k">采纳模式</div><div class="v">{{ playbook.adoptMode ?? '—' }}</div></div>
      </div>
      <div class="lbl" style="margin-top:10px">内容</div>
      <div class="note" style="white-space:pre-wrap;max-height:160px;overflow:auto;margin-top:0;border:1px solid var(--bd);border-radius:4px;padding:10px 12px;color:var(--txt)">{{ playbook.content ?? '（尚无手册，点采纳发布）' }}</div>
    </template>
    <div v-else class="note">尚无作战手册。</div>

    <!-- 档案编辑(共用对话框) -->
    <ProjectEditDialog v-model="editDlg" :project="p" @saved="onSaved" />

    <!-- 协调员维护(复用 CoordinatorPicker) -->
    <CoordinatorPicker v-model="coordDlg" :selected="p.coordinators ?? []" title="维护项目协调员（PUT /projects/{id}/coordinators）" @submit="saveCoordinators" />

    <!-- 减免阶梯维护 -->
    <el-dialog v-model="reduceDlg" title="维护减免阶梯（PUT /projects/{id}/reduce-tiers · 全量覆盖）" width="760px">
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
      <div style="margin-top:6px;color:#909399;font-size:12px">封顶按元录入、提交按分存（capCents）。空折扣行会被忽略；全部清空请用「清空」。</div>
      <template #footer><el-button @click="reduceDlg=false">取消</el-button><el-button type="primary" @click="saveReduce">保存</el-button></template>
    </el-dialog>

    <!-- 作战手册采纳 -->
    <el-dialog v-model="dlg" title="采纳作战手册（POST /projects/{id}/playbook · playbook.adopt）" width="560px">
      <el-form label-width="70px">
        <el-form-item label="版本"><el-input v-model="form.version" placeholder="如 v1.1" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="8" placeholder="作战手册正文（通话前策略/话术指引）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg=false">取消</el-button><el-button type="primary" @click="adopt">采纳发布</el-button></template>
    </el-dialog>
  </div>
</template>
