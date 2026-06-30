<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { api } from '../api/client'
import type { components } from '../api/schema'
import DsDrawer from './DsDrawer.vue'

type ProjectInput = components['schemas']['ProjectInput']

/**
 * 项目新建/编辑共用表单(US-M2-01·H-07)。
 * create→POST /projects(201)，edit→PUT /projects/{id}。
 * commInRate 为 Rate 分数 0-1：UI 录入百分比、提交 ÷100 存(与 ProjectDetailView 口径一致)。
 * 必填(基本信息+缴费标准+收佣比例)缺失阻止提交(US-M2-01 验收)。
 * 协调员：新建/编辑均可在本表单直接设(ProjectInput.coordinatorIds 全量)，
 * 候选 GET /members?role=PC(与 CoordinatorPicker 同源)；编辑后续仍可走 /coordinators 端点维护。
 */
const props = defineProps<{
  modelValue: boolean
  // 编辑时传入当前项目对象(含 id)，新建传 null
  project: any | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', project: any): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

// 协调员候选(本组织 PC·GET /members?role=PC，与 CoordinatorPicker 同源)
const coordOptions = ref<{ id: string; name: string }[]>([])
const coordLoading = ref(false)
async function loadCoordOptions() {
  coordLoading.value = true
  const { data, error } = await api.GET('/members', { params: { query: { role: 'PC', page: 1, size: 200 } } })
  coordLoading.value = false
  if (error) { ElMessage.error('加载协调员候选失败'); return }
  coordOptions.value = (data?.items ?? []).map((m) => ({ id: String(m.id ?? ''), name: m.name || m.username || String(m.id ?? '') }))
}

// 表单模型：commInPct 为百分比展示值；feeRows 数组初始即初始化(防白屏铁律)
function emptyForm() {
  return {
    name: '',
    area: '',
    province: '',
    city: '',
    district: '',
    propCompany: '',
    contractType: '',
    feeRows: [{ biz: '', std: '' }] as { biz?: string; std?: string }[],
    feeCycle: '',
    penalty: '',
    payInfo: '',
    commInPct: 30, // 百分比；提交时 ÷100
    coordinatorIds: [] as string[], // 协调员 ids(数组初始即初始化·防白屏铁律)
    litiCreditCode: '',
    litiLegal: '',
    litiAddr: '',
  }
}
const form = ref(emptyForm())

const rules: FormRules = {
  name: [{ required: true, message: '项目名称必填', trigger: 'blur' }],
  area: [{ required: true, message: '区域必填（基本信息）', trigger: 'blur' }],
  commInPct: [{ required: true, message: '收佣比例必填（BR-M9-01a）', trigger: 'blur' }],
}

// 弹窗打开：编辑预填、新建重置
watch(() => props.modelValue, (open) => {
  if (!open) return
  loadCoordOptions()
  const p = props.project
  if (p) {
    form.value = {
      name: p.name ?? '',
      area: p.area ?? '',
      province: p.province ?? '',
      city: p.city ?? '',
      district: p.district ?? '',
      propCompany: p.propCompany ?? '',
      contractType: p.contractType ?? '',
      feeRows: Array.isArray(p.feeRows) && p.feeRows.length ? p.feeRows.map((r: any) => ({ biz: r.biz ?? '', std: r.std ?? '' })) : [{ biz: '', std: '' }],
      feeCycle: p.feeCycle ?? '',
      penalty: p.penalty ?? '',
      payInfo: p.payInfo ?? '',
      commInPct: p.commInRate != null ? Number((p.commInRate * 100).toFixed(2)) : 30,
      coordinatorIds: Array.isArray(p.coordinators) ? p.coordinators.map((c: any) => String(c.id ?? '')).filter((x: string) => x) : [],
      litiCreditCode: p.litigation?.creditCode ?? '',
      litiLegal: p.litigation?.legal ?? '',
      litiAddr: p.litigation?.addr ?? '',
    }
  } else {
    form.value = emptyForm()
  }
}, { immediate: false })

function close() { emit('update:modelValue', false) }

function buildBody(): ProjectInput {
  const f = form.value
  // 缴费标准描述：至少一行有内容(US-M2-01 缺失阻止提交)
  const feeRows = f.feeRows.filter((r) => (r.biz && r.biz.trim()) || (r.std && r.std.trim()))
  const litigation = (f.litiCreditCode || f.litiLegal || f.litiAddr)
    ? { creditCode: f.litiCreditCode || undefined, legal: f.litiLegal || undefined, addr: f.litiAddr || undefined }
    : null
  const body: ProjectInput = {
    name: f.name,
    area: f.area,
    province: f.province || undefined,
    city: f.city || undefined,
    district: f.district || undefined,
    propCompany: f.propCompany || undefined,
    contractType: f.contractType || undefined,
    feeRows,
    feeCycle: f.feeCycle || undefined,
    penalty: f.penalty || undefined,
    payInfo: f.payInfo || undefined,
    commInRate: Number((Number(f.commInPct) / 100).toFixed(4)),
    litigation,
  }
  // 协调员：仅在有选时带(空数组不下发，避免编辑态误清空已有关联)
  if (Array.isArray(f.coordinatorIds) && f.coordinatorIds.length) body.coordinatorIds = f.coordinatorIds
  return body
}

async function submit() {
  if (!formRef.value) return
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  // 缴费标准描述缺失阻止提交(US-M2-01)
  const hasFee = form.value.feeRows.some((r) => (r.biz && r.biz.trim()) || (r.std && r.std.trim()))
  if (!hasFee) { ElMessage.warning('缴费标准描述缺失，无法提交（US-M2-01）'); return }
  const body = buildBody()
  saving.value = true
  if (props.project?.id) {
    const { data, error } = await api.PUT('/projects/{id}', { params: { path: { id: String(props.project.id) } }, body })
    saving.value = false
    if (error) { ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已更新项目档案'); emit('saved', data); close()
  } else {
    const { data, error } = await api.POST('/projects', { body })
    saving.value = false
    if (error) { ElMessage.error('新建失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已新建项目'); emit('saved', data); close()
  }
}
</script>

<template>
  <DsDrawer :model-value="modelValue" :title="(project?.id ? '编辑项目档案' : '新建项目')" :width="720"
    @update:model-value="(v:boolean)=>emit('update:modelValue', v)">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="项目名称" prop="name"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="区域" prop="area"><el-input v-model="form.area" placeholder="如 杭州·西湖" /></el-form-item>
      <el-form-item label="省/市/区">
        <el-input v-model="form.province" placeholder="省" style="width:30%" />
        <el-input v-model="form.city" placeholder="市" style="width:30%;margin:0 4px" />
        <el-input v-model="form.district" placeholder="区" style="width:30%" />
      </el-form-item>
      <el-form-item label="物业公司"><el-input v-model="form.propCompany" /></el-form-item>
      <el-form-item label="合同类型"><el-input v-model="form.contractType" /></el-form-item>

      <el-divider content-position="left">缴费标准（必填·业态/标准）</el-divider>
      <el-table :data="form.feeRows" border size="small">
        <el-table-column label="业态"><template #default="{row}"><el-input v-model="row.biz" size="small" placeholder="如 住宅" /></template></el-table-column>
        <el-table-column label="标准"><template #default="{row}"><el-input v-model="row.std" size="small" placeholder="如 2.5 元/㎡·月" /></template></el-table-column>
        <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" :disabled="form.feeRows.length<=1" @click="form.feeRows.splice($index,1)">×</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" text type="primary" style="margin-top:6px" @click="form.feeRows.push({ biz: '', std: '' })">+ 添加缴费行</el-button>
      <el-form-item label="缴费周期" style="margin-top:10px"><el-input v-model="form.feeCycle" placeholder="如 年缴/季缴" /></el-form-item>
      <el-form-item label="违约金规则"><el-input v-model="form.penalty" /></el-form-item>
      <el-form-item label="收款信息"><el-input v-model="form.payInfo" type="textarea" :rows="2" /></el-form-item>

      <el-divider content-position="left">资金（收佣比例·必填 BR-M9-01a）</el-divider>
      <el-form-item label="收佣比例(%)" prop="commInPct">
        <el-input-number v-model="form.commInPct" :min="0" :max="100" :step="1" :precision="2" />
        <span style="margin-left:8px;color:#909399">百分比录入，提交按分数 0-1 存（30=0.30）</span>
      </el-form-item>

      <el-divider content-position="left">协调员（PC·决定可见案件范围 BR-M2-13）</el-divider>
      <el-form-item label="协调员">
        <el-select v-model="form.coordinatorIds" multiple filterable clearable :loading="coordLoading" placeholder="选择本组织协调员（PC）" style="width:100%">
          <el-option v-for="c in coordOptions" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <span style="color:#909399;font-size:12px">候选来自本组织 PC（GET /members?role=PC）。新建可直接设，编辑亦可调整（全量覆盖）。</span>
      </el-form-item>

      <el-divider content-position="left">诉讼要素（可后补）</el-divider>
      <el-form-item label="统一社会信用代码"><el-input v-model="form.litiCreditCode" /></el-form-item>
      <el-form-item label="法定代表人"><el-input v-model="form.litiLegal" /></el-form-item>
      <el-form-item label="注册地址"><el-input v-model="form.litiAddr" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ project?.id ? '保存' : '新建' }}</el-button>
    </template>
  </DsDrawer>
</template>
