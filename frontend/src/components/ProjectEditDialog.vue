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
  '四川省': { '成都市': ['锦江区','青羊区','武侯区','成华区','高新区','天府新区','金牛区','双流区','温江区','郫都区','新都区','龙泉驿区'], '绵阳市': ['涪城区','游仙区','安州区'], '宜宾市': ['翠屏区','叙州区','南溪区'], '南充市': ['顺庆区','高坪区','嘉陵区'], '泸州市': ['江阳区','龙马潭区','纳溪区'], '德阳市': ['旌阳区','罗江区'], '达州市': ['通川区','达川区'], '乐山市': ['市中区','五通桥区','沙湾区'] },
  '重庆市': { '重庆市': ['渝中区','江北区','九龙坡区','渝北区','沙坪坝区','南岸区','巴南区','北碚区','大渡口区','万州区','涪陵区','黔江区'] },
  '广东省': { '深圳市': ['南山区','福田区','罗湖区','宝安区','龙岗区','龙华区','光明区','坪山区'], '广州市': ['天河区','越秀区','海珠区','荔湾区','白云区','番禺区','黄埔区','花都区'], '东莞市': ['莞城','东城','南城','万江'], '佛山市': ['禅城区','南海区','顺德区','三水区'] },
  '上海市': { '上海市': ['浦东新区','黄浦区','徐汇区','长宁区','静安区','普陀区','虹口区','杨浦区','闵行区','宝山区','嘉定区','松江区'] },
  '浙江省': { '杭州市': ['西湖区','拱墅区','上城区','滨江区','余杭区','萧山区','临平区','钱塘区'], '宁波市': ['海曙区','鄞州区','江北区','北仑区','镇海区'], '温州市': ['鹿城区','龙湾区','瓯海区'], '嘉兴市': ['南湖区','秀洲区'] },
  '江苏省': { '南京市': ['鼓楼区','建邺区','秦淮区','江宁区','玄武区','栖霞区','雨花台区','浦口区'], '苏州市': ['姑苏区','虎丘区','吴中区','相城区','吴江区','工业园区'], '无锡市': ['梁溪区','滨湖区','新吴区','惠山区'], '常州市': ['天宁区','钟楼区','新北区'] },
  '湖北省': { '武汉市': ['武昌区','洪山区','江岸区','江汉区','硚口区','汉阳区','青山区','东西湖区'], '宜昌市': ['西陵区','伍家岗区','点军区'], '襄阳市': ['襄城区','樊城区','襄州区'] },
  '北京市': { '北京市': ['朝阳区','海淀区','丰台区','东城区','西城区','通州区','大兴区','石景山区','昌平区','顺义区','房山区'] },
  '天津市': { '天津市': ['和平区','河东区','河西区','南开区','河北区','红桥区','滨海新区','北辰区','西青区'] },
  '山东省': { '济南市': ['历下区','市中区','槐荫区','天桥区','历城区','长清区'], '青岛市': ['市南区','市北区','黄岛区','崂山区','城阳区','即墨区'], '烟台市': ['芝罘区','福山区','莱山区'], '潍坊市': ['潍城区','奎文区','寒亭区'] },
  '河南省': { '郑州市': ['中原区','二七区','管城区','金水区','惠济区'], '洛阳市': ['涧西区','西工区','老城区','洛龙区'], '开封市': ['鼓楼区','龙亭区','禹王台区'] },
  '福建省': { '福州市': ['鼓楼区','台江区','仓山区','晋安区','马尾区'], '厦门市': ['思明区','湖里区','集美区','海沧区'], '泉州市': ['鲤城区','丰泽区','洛江区'] },
  '湖南省': { '长沙市': ['岳麓区','芙蓉区','天心区','开福区','雨花区','望城区'], '株洲市': ['天元区','芦淞区','荷塘区'], '湘潭市': ['岳塘区','雨湖区'] },
  '安徽省': { '合肥市': ['蜀山区','包河区','庐阳区','瑶海区','滨湖新区'], '芜湖市': ['镜湖区','鸠江区','弋江区'] },
  '陕西省': { '西安市': ['雁塔区','未央区','碑林区','莲湖区','新城区','长安区'], '咸阳市': ['秦都区','渭城区'] },
  '江西省': { '南昌市': ['东湖区','西湖区','青云谱区','青山湖区','新建区'], '赣州市': ['章贡区','南康区','赣县区'] },
  '河北省': { '石家庄市': ['长安区','桥西区','新华区','裕华区'], '唐山市': ['路北区','路南区','丰润区'], '保定市': ['竞秀区','莲池区'] },
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
    commInPct: 10,
    coordinatorIds: [] as string[],
    litiCreditCode: '',
    litiLegal: '',
    litiAddr: '',
    reduceEnabled: false,
    reduceTiers: [{ discountPct: 10, capYuan: null as number | null, waivePenalty: false, decide: 'COLLECTOR_SELF' }] as { discountPct: number; capYuan: number | null; waivePenalty: boolean; decide: string }[],
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
      commInPct: p.commInRate != null ? Number((p.commInRate * 100).toFixed(2)) : 10,
      coordinatorIds: Array.isArray(p.coordinators) ? p.coordinators.map((c: any) => String(c.id ?? '')).filter((x: string) => x) : [],
      litiCreditCode: p.litigation?.creditCode ?? '',
      litiLegal: p.litigation?.legal ?? '',
      litiAddr: p.litigation?.addr ?? '',
      reduceEnabled: Array.isArray(p.reduceTiers) && p.reduceTiers.length > 0,
      reduceTiers: Array.isArray(p.reduceTiers) ? p.reduceTiers.map((t: any) => {
        const d = (t.discount ?? '') as string; const m = (d || '').match(/[\d.]+/); const n = m ? parseFloat(m[0]) : NaN
        const pct = !isNaN(n) ? (n < 10 ? 100 - Math.round(n * 10) : 100 - Math.round(n)) : 10
        return { discountPct: pct, capYuan: t.capCents != null ? Number((t.capCents / 100).toFixed(0)) : null, waivePenalty: t.waivePenalty ?? false, decide: t.decide || 'COLLECTOR_SELF' }
      }) : [{ discountPct: 10, capYuan: null, waivePenalty: false, decide: 'COLLECTOR_SELF' }],
      playbook: p.playbookContent ?? '',
      status: p.status ?? '启用',
    }
  } else {
    form.value = emptyForm()
  }
}, { immediate: false })

function close() { emit('update:modelValue', false) }

// ── 合同文件（纯前端选取，不上传；后端补齐文件服务后再恢复 el-upload）──
const fileInput = ref<HTMLInputElement | null>(null)
function onContractFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files) return
  for (let i = 0; i < files.length; i++) {
    const f = files[i]
    form.value.contractFiles.push({ name: f.name, url: '' })
  }
  input.value = '' // 允许重复选同名文件
}
function removeContractFile(i: number) { form.value.contractFiles.splice(i, 1) }

// ── 减免阶梯行 ──
function addReduceTier() {
  form.value.reduceTiers.push({ discountPct: 10, capYuan: null, waivePenalty: false, decide: 'COLLECTOR_SELF' })
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
  const tiers = f.reduceTiers.filter((t) => t.discountPct > 0)
  if (!tiers.length) return
  const body = tiers.map((t) => {
    const p = 100 - t.discountPct // e.g. 10→90, 20→80, 5→95
    const label = p % 10 === 0 ? String(p / 10) + '折' : String(p) + '折'
    return { discount: label, capCents: t.capYuan != null ? Math.round(t.capYuan * 100) : null, waivePenalty: t.waivePenalty, decide: t.decide }
  })
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
          <el-select v-model="form.city" style="width:100%" placeholder="选择城市" :disabled="!form.province" @change="onCityChange">
            <el-option v-for="c in cityOpts" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="区 / 县">
          <el-select v-model="form.district" style="width:100%" placeholder="选择区县" :disabled="!form.city">
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
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 16px;margin-top:10px">
        <el-form-item label="收费周期">
          <el-select v-model="form.feeCycle" style="width:100%">
            <el-option v-for="x in FEE_CYCLES" :key="x" :label="x" :value="x" />
          </el-select>
        </el-form-item>
        <el-form-item label="违约金"><el-input v-model="form.penalty" placeholder="如：欠费按日 0.05%" /></el-form-item>
      </div>

      <!-- 收佣比例（独立一行，编号承接③收费标准） -->
      <el-divider content-position="left" style="margin-top:12px">④ 平台佣金（收佣比例）</el-divider>
      <el-form-item label="收佣比例(%) *" prop="commInPct">
        <el-input-number v-model="form.commInPct" :min="0" :max="100" :step="1" :precision="2" style="width:200px" />
        <span style="margin-left:12px;color:#909399;font-size:13px">
          物业支付给平台的佣金比例。例如 12 表示 12%，即平台按每笔回款金额的 12% 收取佣金。项目级为默认值，批次导入时可逐批覆盖（CFG-COMM-IN）。
        </span>
      </el-form-item>

      <!-- ④ 收款信息 -->
      <el-divider content-position="left">⑤ 收款信息</el-divider>
      <el-form-item label="收款信息"><el-input v-model="form.payInfo" type="textarea" :rows="2" placeholder="开户银行 / 银行账号 / 账户名称 / 微信收款渠道 等" /></el-form-item>

      <!-- ⑤ 协调员 -->
      <el-divider content-position="left">⑥ 指定协调员（可多选）</el-divider>
      <el-form-item label="协调员">
        <el-select v-model="form.coordinatorIds" multiple filterable clearable :loading="coordLoading" placeholder="选择本组织协调员（PC）" style="width:100%">
          <el-option v-for="c in coordOptions" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <span style="color:#909399;font-size:12px">候选来自本组织 PC。新建可直接设，编辑亦可调整（全量覆盖）。</span>
      </el-form-item>

      <!-- ⑥ 合同 -->
      <el-divider content-position="left">⑦ 合同</el-divider>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:0 16px">
        <el-form-item label="合同类型">
          <el-select v-model="form.contractType" style="width:100%">
            <el-option v-for="t in CONTRACT_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="合同附件">
          <input ref="fileInput" type="file" multiple style="display:none" @change="onContractFileChange" />
          <el-button size="small" @click="fileInput?.click()">+ 选取合同文件</el-button>
          <div v-if="form.contractFiles.length" style="margin-top:6px;display:flex;flex-wrap:wrap;gap:6px">
            <el-tag v-for="(f, i) in form.contractFiles" :key="i" closable @close="removeContractFile(i)" type="info" size="small">{{ f.name }}</el-tag>
          </div>
          <span v-else style="color:#909399;font-size:12px">未选择</span>
        </el-form-item>
      </div>

      <!-- ⑦ 减免政策 -->
      <el-divider content-position="left">⑧ 减免政策（项目级底盘 · 后期可在项目档案修改）</el-divider>
      <el-checkbox v-model="form.reduceEnabled" style="margin-bottom:8px">创建时设置减免政策</el-checkbox>
      <template v-if="form.reduceEnabled">
        <div style="color:#909399;font-size:12px;margin-bottom:8px">
          阶梯折扣 + 是否减免违约金 + 决定权：<b>催收员自决</b>档系统直接生效；<b>线下内部流程</b>档系统仅提示并留痕。
        </div>
        <el-table :data="form.reduceTiers" border size="small">
          <el-table-column label="减免 %" width="130"><template #default="{row}"><el-input-number v-model="row.discountPct" size="small" :min="1" :max="99" :step="1" style="width:100%" /> <span style="font-size:12px;color:var(--sec)">%（{{ (100 - row.discountPct) % 10 === 0 ? (100 - row.discountPct) / 10 : (100 - row.discountPct) }}折）</span></template></el-table-column>
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
      <el-divider content-position="left">⑨ 作战手册（催收策略/注意事项）</el-divider>
      <el-input v-model="form.playbook" type="textarea" :rows="4" placeholder="本小区催收策略 / 沟通口径 / 注意事项（AI 复盘与话术会参考）" />

      <!-- 诉讼要素（注册地址，其他已在 ① 基本信息中） -->
      <el-divider content-position="left">⑩ 诉讼要素（注册地址可后补）</el-divider>
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
