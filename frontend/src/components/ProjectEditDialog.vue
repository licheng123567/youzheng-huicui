<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { api } from '../api/client'
import type { components } from '../api/schema'
import { reduceDecideLabel } from '../constants/enums'
import DsDrawer from './DsDrawer.vue'

type ProjectInput = components['schemas']['ProjectInput']

// ── 常量 ──
const REGION: Record<string, Record<string, string[]>> = {
  '四川省': { '成都市': ['锦江区','青羊区','武侯区','成华区','高新区','天府新区'], '绵阳市': ['涪城区','游仙区','安州区'] },
  '重庆市': { '重庆市': ['渝中区','江北区','九龙坡区','渝北区'] },
  '广东省': { '深圳市': ['南山区','福田区','罗湖区','宝安区'], '广州市': ['天河区','越秀区','海珠区'] },
  '上海市': { '上海市': ['浦东新区','黄浦区','徐汇区','长宁区'] },
  '浙江省': { '杭州市': ['西湖区','拱墅区','上城区','滨江区','余杭区'], '宁波市': ['海曙区','鄞州区','江北区'] },
  '江苏省': { '南京市': ['鼓楼区','建邺区','秦淮区','江宁区'], '苏州市': ['姑苏区','虎丘区','吴中区'] },
  '湖北省': { '武汉市': ['武昌区','洪山区','江岸区','江汉区'] },
  '北京市': { '北京市': ['朝阳区','海淀区','丰台区','东城区','西城区','通州区','大兴区'] },
}
const CONTRACT_TYPES = ['物业服务合同（包干制）', '物业服务合同（酬金制）', '前期物业服务合同', '临时管理规约']
const FEE_CYCLES = ['月付', '季付', '半年付', '年付', '一次性']
const DECIDE_OPTS = ['COLLECTOR_SELF', 'OFFLINE_INTERNAL']

// ── 地址级联 ──
const provinceOpts = computed(() => Object.keys(REGION))
const cityOpts = computed(() => Object.keys(REGION[form.value.province] || {}))
const districtOpts = computed(() => (REGION[form.value.province] || {})[form.value.city] || [])

function onProvinceChange() {
  form.value.city = ''
  form.value.district = ''
}
function onCityChange() {
  form.value.district = ''
}

// ── props / emit ──
const props = defineProps<{
  modelValue: boolean
  project: any | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', project: any): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

// ── 协调员候选 ──
const coordOptions = ref<{ id: string; name: string }[]>([])
const coordLoading = ref(false)
async function loadCoordOptions() {
  coordLoading.value = true
  const { data, error } = await api.GET('/members', { params: { query: { role: 'PC', page: 1, size: 200 } } })
  coordLoading.value = false
  if (error) { ElMessage.error('加载协调员候选失败'); return }
  coordOptions.value = (data?.items ?? []).map((m: any) => ({ id: String(m.id ?? ''), name: m.name || m.username || String(m.id ?? '') }))
}

// ── 表单模型 ──
function emptyForm() {
  return {
    name: '',
    propCompany: '',
    propPhone: '',
    propAddr: '',
    province: '四川省',
    city: '',
    district: '',
    contractType: CONTRACT_TYPES[0],
    contractFiles: [] as { name: string; url: string }[],
    feeRows: [{ biz: '住宅', std: '2.5 元/㎡·月' }] as { biz?: string; std?: string }[],
    feeCycle: '季付',
    penalty: '',
    payInfo: '',
    commInPct: 30,
    coordinatorIds: [] as string[],
    litiCreditCode: '',
    litiLegal: '',
    litiAddr: '',
    reduceEnabled: false,
    reduceTiers: [{ discount: '9折', capYuan: null as number | null, waivePenalty: false, decide: 'COLLECTOR_SELF' }] as { discount: string; capYuan: number | null; waivePenalty: boolean; decide: string }[],
    playbook: '',
    status: '启用',
  }
}
const form = ref(emptyForm())

const rules: FormRules = {
  name: [{ required: true, message: '项目名称必填', trigger: 'blur' }],
  propCompany: [{ required: true, message: '物业公司必填（诉讼原告）', trigger: 'blur' }],
  commInPct: [{ required: true, message: '收佣比例必填', trigger: 'blur' }],
}

// ── 弹窗打开/关闭 ──
watch(() => props.modelValue, (open) => {
  if (!open) return
  loadCoordOptions()
  const p = props.project
  if (p) {
    form.value = {
      name: p.name ?? '',
      propCompany: p.propCompany ?? '',
      propPhone: p.propPhone ?? p.litigation?.phone ?? '',
      propAddr: p.propAddr ?? p.litigation?.addr ?? '',
      province: p.province || '四川省',
      city: p.city || '',
      district: p.district || '',
      contractType: p.contractType || CONTRACT_TYPES[0],
      contractFiles: Array.isArray(p.contractFiles) ? p.contractFiles.map((f: any) => ({ name: f.name ?? '', url: f.url ?? '' })) : [],
      feeRows: Array.isArray(p.feeRows) && p.feeRows.length ? p.feeRows.map((r: any) => ({ biz: r.biz ?? '', std: r.std ?? '' })) : [{ biz: '', std: '' }],
      feeCycle: p.feeCycle || '季付',
      penalty: p.penalty ?? '',
      payInfo: p.payInfo ?? '',
      commInPct: p.commInRate != null ? Number((p.commInRate * 100).toFixed(2)) : 30,
      coordinatorIds: Array.isArray(p.coordinators) ? p.coordinators.map((c: any) => String(c.id ?? '')).filter((x: string) => x) : [],
      litiCreditCode: p.litigation?.creditCode ?? '',
      litiLegal: p.litigation?.legal ?? '',
      litiAddr: p.litigation?.addr ?? '',
      reduceEnabled: Array.isArray(p.reduceTiers) && p.reduceTiers.length > 0,
      reduceTiers: Array.isArray(p.reduceTiers) ? p.reduceTiers.map((t: any) => ({
        discount: t.discount ?? '', capYuan: t.capCents != null ? Number((t.capCents / 100).toFixed(0)) : null, waivePenalty: t.waivePenalty ?? false, decide: t.decide || 'COLLECTOR_SELF',
      })) : [{ discount: '', capYuan: null, waivePenalty: false, decide: 'COLLECTOR_SELF' }],
      playbook: p.playbookContent ?? '',
      status: p.status ?? '启用',
    }
  } else {
    form.value = emptyForm()
  }
}, { immediate: false })

function close() { emit('update:modelValue', false) }

// ── 合同文件上传 ──
function onContractUpload(resp: any, file: any) {
  const url = resp?.url ?? resp?.fileUrl ?? resp?.data?.url ?? ''
  if (url) {
    form.value.contractFiles.push({ name: file?.name ?? url, url })
    ElMessage.success('合同附件已上传')
  } else {
    form.value.contractFiles.push({ name: file?.name ?? '文件', url: '' })
  }
}
function onContractUploadError() { ElMessage.error('上传失败') }
function removeContractFile(i: number) { form.value.contractFiles.splice(i, 1) }

// ── 减免阶梯行 ──
function addReduceTier() {
  form.value.reduceTiers.push({ discount: '', capYuan: null, waivePenalty: false, decide: 'COLLECTOR_SELF' })
}
function removeReduceTier(i: number) { form.value.reduceTiers.splice(i, 1) }

// ── 构建提交 body ──
function buildBody(): ProjectInput {
  const f = form.value
  const feeRows = f.feeRows.filter((r) => (r.biz && r.biz.trim()) || (r.std && r.std.trim()))
  const litigation = (f.litiCreditCode || f.litiLegal || f.litiAddr || f.propPhone || f.propAddr)
    ? { creditCode: f.litiCreditCode || undefined, legal: f.litiLegal || undefined, addr: f.litiAddr || f.propAddr || undefined }
    : null
  const area = [f.province, f.city, f.district].filter(Boolean).join('')
  const body: any = {
    name: f.name,
    area: area || [f.province, f.city].filter(Boolean).join(''),
    province: f.province || undefined,
    city: f.city || undefined,
    district: f.district || undefined,
    propCompany: f.propCompany || undefined,
    propPhone: f.propPhone || undefined,
    propAddr: f.propAddr || undefined,
    contractType: f.contractType || undefined,
    status: f.status || undefined,
    feeRows,
    feeCycle: f.feeCycle || undefined,
    penalty: f.penalty || undefined,
    payInfo: f.payInfo || undefined,
    commInRate: Number((Number(f.commInPct) / 100).toFixed(4)),
    litigation,
  }
  if (Array.isArray(f.coordinatorIds) && f.coordinatorIds.length) body.coordinatorIds = f.coordinatorIds
  // 合同附件加入 body（后端若 strip-unknown 则仅 UI 保留）
  if (f.contractFiles.length) body.contractFiles = f.contractFiles
  return body
}

// ── 提交 ──
async function submit() {
  if (!formRef.value) return
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  const hasFee = form.value.feeRows.some((r) => (r.biz && r.biz.trim()) || (r.std && r.std.trim()))
  if (!hasFee) { ElMessage.warning('缴费标准描述缺失，无法提交'); return }
  if (!form.value.city) { ElMessage.warning('请选择所在城市'); return }

  const body = buildBody()
  saving.value = true

  let projectId = props.project?.id ? String(props.project.id) : ''

  if (projectId) {
    // 编辑：更新项目主体
    const { data, error } = await api.PUT('/projects/{id}', { params: { path: { id: projectId } }, body })
    if (error) { saving.value = false; ElMessage.error('保存失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已更新项目档案')
    // 编辑时也同步减免/手册
    await syncReduce(projectId)
    await syncPlaybook(projectId)
    emit('saved', data)
  } else {
    // 新建
    const { data, error } = await api.POST('/projects', { body })
    if (error) { saving.value = false; ElMessage.error('新建失败：' + ((error as any)?.message ?? '')); return }
    projectId = String((data as any)?.id ?? '')
    ElMessage.success('已新建项目')
    await syncReduce(projectId)
    await syncPlaybook(projectId)
    emit('saved', data)
  }
  saving.value = false
  close()
}

async function syncReduce(projectId: string) {
  const f = form.value
  if (!f.reduceEnabled) return
  const tiers = f.reduceTiers.filter((t) => t.discount.trim())
  if (!tiers.length) return
  const body = tiers.map((t) => ({
    discount: t.discount,
    capCents: t.capYuan != null ? Math.round(t.capYuan * 100) : null,
    waivePenalty: t.waivePenalty,
    decide: t.decide,
  }))
  await api.PUT('/projects/{id}/reduce-tiers', { params: { path: { id: projectId } }, body } as any)
}

async function syncPlaybook(projectId: string) {
  const f = form.value
  if (!f.playbook.trim()) return
  await api.POST('/projects/{id}/playbook', {
    params: { path: { id: projectId } },
    body: { version: 'v1.0', content: f.playbook },
  } as any)
}
</script>

<template>
  <DsDrawer :model-value="modelValue" :title="(project?.id ? '编辑项目档案' : '新建项目')" :width="740"
    @update:model-value="(v:boolean)=>emit('update:modelValue', v)">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
        title="以下信息同时作为要素化诉状（物业服务合同纠纷）的生成要素。带 * 为必填。" />

      <!-- ① 基本信息 -->
      <el-divider content-position="left">① 基本信息</el-divider>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 16px">
        <el-form-item label="项目名称（小区） *" prop="name"><el-input v-model="form.name" placeholder="如：阳光花园二期" /></el-form-item>
        <el-form-item label="物业公司（诉讼原告） *" prop="propCompany"><el-input v-model="form.propCompany" placeholder="如：成都阳光物业服务有限公司" /></el-form-item>
        <el-form-item label="统一社会信用代码"><el-input v-model="form.litiCreditCode" placeholder="91510100XXXXXXXXXX" /></el-form-item>
        <el-form-item label="法定代表人 / 负责人"><el-input v-model="form.litiLegal" placeholder="如：张三" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.propPhone" placeholder="如：028-8888XXXX" /></el-form-item>
        <el-form-item label="物业 / 小区地址（房屋坐落）"><el-input v-model="form.propAddr" placeholder="如：武侯区XX路XX号" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:100%">
            <el-option label="启用" value="启用" />
            <el-option label="停用" value="停用" />
          </el-select>
        </el-form-item>
      </div>

      <!-- ② 所在区域 -->
      <el-divider content-position="left">② 所在区域（省 / 市 / 区县）</el-divider>
      <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:0 16px">
        <el-form-item label="省 *">
          <el-select v-model="form.province" style="width:100%" @change="onProvinceChange">
            <el-option v-for="p in provinceOpts" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="市 *">
          <el-select v-model="form.city" style="width:100%" :disabled="!form.province" @change="onCityChange">
            <el-option value="" label="选择城市" />
            <el-option v-for="c in cityOpts" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="区 / 县">
          <el-select v-model="form.district" style="width:100%" :disabled="!form.city">
            <el-option value="" label="选择区县" />
            <el-option v-for="d in districtOpts" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
      </div>

      <!-- ③ 收费标准 -->
      <el-divider content-position="left">③ 收费标准（按业态多行）</el-divider>
      <el-table :data="form.feeRows" border size="small">
        <el-table-column label="业态"><template #default="{row}"><el-input v-model="row.biz" size="small" placeholder="住宅 / 商铺 / 车位…" /></template></el-table-column>
        <el-table-column label="收费标准"><template #default="{row}"><el-input v-model="row.std" size="small" placeholder="如：2.5 元/㎡·月" /></template></el-table-column>
        <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" :disabled="form.feeRows.length<=1" @click="form.feeRows.splice($index,1)">×</el-button></template></el-table-column>
      </el-table>
      <el-button size="small" text type="primary" style="margin-top:6px" @click="form.feeRows.push({ biz: '', std: '' })">+ 增加业态</el-button>
      <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:0 16px;margin-top:10px">
        <el-form-item label="收费周期">
          <el-select v-model="form.feeCycle" style="width:100%">
            <el-option v-for="x in FEE_CYCLES" :key="x" :label="x" :value="x" />
          </el-select>
        </el-form-item>
        <el-form-item label="违约金"><el-input v-model="form.penalty" placeholder="如：欠费按日 0.05%" /></el-form-item>
        <el-form-item label="收佣比例(%) *" prop="commInPct">
          <el-input-number v-model="form.commInPct" :min="0" :max="100" :step="1" :precision="2" style="width:100%" />
        </el-form-item>
      </div>
      <div class="note" style="margin-top:4px">收佣比例 = 平台向物业收取的佣金比例。项目级为默认，批次导入时可逐批覆盖。</div>

      <!-- ④ 收款信息 -->
      <el-divider content-position="left">④ 收款信息</el-divider>
      <el-form-item label="收款信息"><el-input v-model="form.payInfo" type="textarea" :rows="2" placeholder="开户银行 / 银行账号 / 账户名称 / 微信收款渠道 等" /></el-form-item>

      <!-- ⑤ 协调员 -->
      <el-divider content-position="left">⑤ 指定协调员（可多选）</el-divider>
      <el-form-item label="协调员">
        <el-select v-model="form.coordinatorIds" multiple filterable clearable :loading="coordLoading" placeholder="选择本组织协调员（PC）" style="width:100%">
          <el-option v-for="c in coordOptions" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <span style="color:#909399;font-size:12px">候选来自本组织 PC。新建可直接设，编辑亦可调整（全量覆盖）。</span>
      </el-form-item>

      <!-- ⑥ 合同 -->
      <el-divider content-position="left">⑥ 合同</el-divider>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 16px">
        <el-form-item label="合同类型">
          <el-select v-model="form.contractType" style="width:100%">
            <el-option v-for="t in CONTRACT_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="合同附件">
          <el-upload action="/api/uploads" :show-file-list="false" :on-success="onContractUpload" :on-error="onContractUploadError">
            <el-button size="small">+ 上传合同附件</el-button>
          </el-upload>
          <div v-if="form.contractFiles.length" style="margin-top:6px;display:flex;flex-wrap:wrap;gap:6px">
            <el-tag v-for="(f, i) in form.contractFiles" :key="i" closable @close="removeContractFile(i)" type="info" size="small">{{ f.name }}</el-tag>
          </div>
          <span v-else style="color:#909399;font-size:12px">未上传</span>
        </el-form-item>
      </div>

      <!-- ⑦ 减免政策 -->
      <el-divider content-position="left">⑦ 减免政策（项目级底盘 · 后期可在项目档案修改）</el-divider>
      <el-checkbox v-model="form.reduceEnabled" style="margin-bottom:8px">创建时设置减免政策</el-checkbox>
      <template v-if="form.reduceEnabled">
        <div style="color:#909399;font-size:12px;margin-bottom:8px">
          阶梯折扣 + 是否减免违约金 + 决定权：<b>催收员自决</b>档系统直接生效；<b>线下内部流程</b>档系统仅提示并留痕。
        </div>
        <el-table :data="form.reduceTiers" border size="small">
          <el-table-column label="折扣" width="120"><template #default="{row}"><el-input v-model="row.discount" size="small" placeholder="如 9折" /></template></el-table-column>
          <el-table-column label="减免上限(元)"><template #default="{row}"><el-input-number v-model="row.capYuan" size="small" :min="0" :controls="false" placeholder="不限" style="width:100%" /></template></el-table-column>
          <el-table-column label="含违约金减免" width="110"><template #default="{row}"><el-switch v-model="row.waivePenalty" size="small" /></template></el-table-column>
          <el-table-column label="决定权" width="150"><template #default="{row}">
            <el-select v-model="row.decide" size="small" style="width:100%">
              <el-option v-for="d in DECIDE_OPTS" :key="d" :label="reduceDecideLabel(d)" :value="d" />
            </el-select>
          </template></el-table-column>
          <el-table-column width="50"><template #default="{$index}"><el-button size="small" text type="danger" @click="removeReduceTier($index)">×</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" text type="primary" style="margin-top:6px" @click="addReduceTier">+ 增加阶梯</el-button>
      </template>

      <!-- ⑧ 作战手册 -->
      <el-divider content-position="left">⑧ 作战手册（催收策略/注意事项）</el-divider>
      <el-input v-model="form.playbook" type="textarea" :rows="4" placeholder="本小区催收策略 / 沟通口径 / 注意事项（AI 复盘与话术会参考）" />

      <!-- 诉讼要素（注册地址，其他已在 ① 基本信息中） -->
      <el-divider content-position="left">⑨ 诉讼要素（注册地址可后补）</el-divider>
      <el-form-item label="注册地址"><el-input v-model="form.litiAddr" placeholder="如：成都市武侯区XX路XX号" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ project?.id ? '保存修改' : '创建项目' }}</el-button>
    </template>
  </DsDrawer>
</template>

<style scoped>
.note { color: #909399; font-size: 12px; }
</style>
