<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import ProjectEditDialog from '../components/ProjectEditDialog.vue'
import CoordinatorPicker from '../components/CoordinatorPicker.vue'

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
const decideLabel = (d?: string) => d === 'COLLECTOR_SELF' ? '催收员自决' : d === 'OFFLINE_INTERNAL' ? '线下内部流程' : d === 'PL_APPROVE' ? '物业负责人审批' : (d ?? '—')

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
  <el-card v-if="p">
    <template #header>
      <el-button link @click="router.back()">← 返回</el-button>
      <span style="margin-left:8px">项目详情：{{ p.name }}（视角 {{ p.viewRole }}）</span>
      <el-button v-if="auth.has('proj.edit')" size="small" type="primary" style="margin-left:12px" @click="openEdit">编辑档案</el-button>
    </template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="ID">{{ p.id }}</el-descriptions-item>
      <el-descriptions-item label="区域">{{ p.area }}</el-descriptions-item>
      <el-descriptions-item label="物业公司">{{ p.propCompany ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="合同类型">{{ p.contractType ?? '—' }}</el-descriptions-item>
      <!-- 资金双线：收佣比例整项仅平台/物业渲染，服务商视角字段级无→整项不出(H-03) -->
      <el-descriptions-item v-if="showCommInRate" label="收佣比例">{{ ratePct(p.commInRate) }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ p.status }}</el-descriptions-item>
    </el-descriptions>

    <!-- US-M2-02 关联协调员 -->
    <el-divider content-position="left">
      关联协调员（PC↔项目 多对多 BR-M2-13）
      <el-button v-if="auth.has('proj.edit')" size="small" text type="primary" @click="openCoord">维护协调员</el-button>
    </el-divider>
    <div v-if="p.coordinators && p.coordinators.length">
      <el-tag v-for="c in p.coordinators" :key="c.id" style="margin-right:6px">{{ c.name || c.id }}</el-tag>
    </div>
    <el-empty v-else description="尚未关联协调员" :image-size="40" />

    <!-- BR-M2-18a 减免阶梯 -->
    <el-divider content-position="left">
      减免阶梯（BR-M2-18a 阶梯+决定权）
      <el-button v-if="auth.has('reduce.policy.edit')" size="small" text type="primary" @click="openReduce">维护减免规则</el-button>
      <el-button v-if="auth.has('reduce.policy.edit') && p.reduceTiers && p.reduceTiers.length" size="small" text @click="clearReduce">清空</el-button>
    </el-divider>
    <el-table v-if="p.reduceTiers && p.reduceTiers.length" :data="p.reduceTiers" border size="small">
      <el-table-column prop="discount" label="折扣" />
      <el-table-column label="封顶"><template #default="{row}">{{ yuan(row.capCents) }}</template></el-table-column>
      <el-table-column label="决定权"><template #default="{row}">{{ decideLabel(row.decide) }}</template></el-table-column>
      <el-table-column label="免违约金"><template #default="{row}">{{ row.waivePenalty?'是':'否' }}</template></el-table-column>
    </el-table>
    <el-empty v-else description="尚无减免阶梯" :image-size="40" />

    <!-- US-M5-07 作战手册 -->
    <el-divider content-position="left">作战手册（GET /projects/{id}/playbook · AI 产草稿→物业采纳才发布 US-M5-07）
      <el-button v-if="auth.has('playbook.adopt')" size="small" text type="primary" @click="openAdopt">采纳/编辑</el-button>
    </el-divider>
    <el-descriptions v-if="playbook" :column="2" border size="small">
      <el-descriptions-item label="版本">{{ playbook.version ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="采纳模式">{{ playbook.adoptMode ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="内容" :span="2"><div style="white-space:pre-wrap;max-height:160px;overflow:auto">{{ playbook.content ?? '（尚无手册，点采纳发布）' }}</div></el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="尚无作战手册" :image-size="50" />

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
  </el-card>
</template>
