<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'
import { useAuth } from '../stores/auth'
import { roleLabel, roleHint } from '../constants/roles'
import { permLabel } from '../constants/permissions'
import { orgTypeLabel, statusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// 成员管理(M1·member.manage)：本组织成员 CRUD/停用启用/重置密码 + 工作督导(BR-M10-10)。
// 更正旧注释「督导为平台功能 VL 不涉及」——错误：督导端点 x-permission=member.manage、scope=own-org，
// 负责人(PL 督协调员 / VL 督催收员)对本组织成员完全可督导留痕。
const auth = useAuth()
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))
const members = ref<any[]>([])
// 分页：此前硬编码 page:1 —— 第 51 个成员既看不见也无从得知。
const page = ref(1)
const size = ref(50)
const total = ref(0)
function onPage(p: number) { page.value = p; load() }
const orgs = ref<any[]>([])
const isPlatform = () => auth.has('org.manage')
// 工作督导 tab：仅组织负责人(有 member.manage 且非平台) 可见——VL 督催收员 / PL 督协调员。
const showSupervise = computed(() => auth.has('member.manage') && !isPlatform())
const memberTab = ref<'list' | 'supervise'>('list')
const superviseNoun = computed(() => ((auth.me as any)?.org?.type === 'PROVIDER') ? '催收员' : '协调员')


// BR-M1-04a：角色下拉按当前组织类型过滤，与后端 MemberM1Controller 允许范围严格一致。
// PROPERTY→只建协调员(PC)；PROVIDER→只建催收员(CO)；PLATFORM→建平台员工(SA/SE)。
// PL/VL 为负责人角色，由 POST /orgs 绑定，不经此入口建立。
const roleOptions = computed<string[]>(() => {
  const orgType: string = (auth.me as any)?.org?.type ?? ''
  if (orgType === 'PLATFORM') return ['SA', 'SE']
  if (orgType === 'PROPERTY') return ['PC']
  if (orgType === 'PROVIDER') return ['CO']
  return ['PC'] // 保守兜底
})

// 当前主体可授予的权限上限（Me.permissions）
const myPermissions = computed<string[]>(() => (auth.me as any)?.permissions ?? [])

async function load() {
  // v1.21.1 平台只看平台成员：与「平台只建平台员工(SA/SE)·BR-M1-04a」的写口径对齐。
  // 端点本身对平台仍是全量（CoordinatorPicker 靠它跨组织挑物业协调员），故收窄在页面这一层用 orgId 参数。
  // 服务商/物业的成员由各自负责人在自己的成员管理里维护，平台既不看也不管。
  const q: any = { page: page.value, size: size.value }
  if (isPlatform()) q.orgId = String((auth.me as any)?.org?.id ?? '')
  const m = await api.GET('/members', { params: { query: q } as any })
  members.value = (m.data as any)?.items ?? []
  total.value = (m.data as any)?.meta?.total ?? 0
  if (isPlatform()) orgs.value = ((await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
  if (showSupervise.value) loadSupervise()
}

// ── 工作督导（BR-M10-10）──
const caps = ref<any[]>([])            // /providers/{id}/collector-capacity（持有/今日动作/今日回款）
const capHoldCap = ref(0)
const supervisions = ref<any[]>([])    // /members/supervision（督导记录）
async function loadSupervise() {
  const orgId = (auth.me as any)?.org?.id
  const [cap, sup] = await Promise.all([
    orgId ? api.GET('/providers/{id}/collector-capacity', { params: { path: { id: String(orgId) } } } as any) : Promise.resolve({ data: null }),
    api.GET('/members/supervision', { params: { query: { page: 1, size: 50 } } as any }),
  ])
  caps.value = (cap.data as any)?.items ?? []
  capHoldCap.value = (cap.data as any)?.holdCap ?? 0
  supervisions.value = (sup.data as any)?.items ?? []
}
const capOf = (memberId: string) => caps.value.find((c) => String(c.collectorId) === String(memberId))
const supCountOf = (memberId: string) => supervisions.value.filter((r) => String(r.memberId) === String(memberId)).length
const SUP_ACTIONS = [
  { v: 'REMIND', label: '提醒' }, { v: 'TALK', label: '督导谈话' },
  { v: 'TRAINING', label: '安排培训' }, { v: 'NOTE', label: '记录' },
]
const SUP_LABEL: Record<string, string> = { REMIND: '提醒', TALK: '督导谈话', TRAINING: '安排培训', NOTE: '记录' }
const supDlg = ref(false)
const supForm = ref<{ memberId: string; memberName: string; action: string; note: string }>({ memberId: '', memberName: '', action: 'REMIND', note: '' })
function openSupervise(m: any) {
  supForm.value = { memberId: String(m.id), memberName: m.name, action: 'REMIND', note: '' }
  supDlg.value = true
}
async function submitSupervise() {
  const { error } = await api.POST('/members/{id}/supervision-actions', {
    params: { path: { id: supForm.value.memberId } },
    body: { action: supForm.value.action, note: supForm.value.note || undefined } as any,
  })
  if (error) { ElMessage.error('督导留痕失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已记录督导（' + SUP_LABEL[supForm.value.action] + '）')
  supDlg.value = false
  loadSupervise()
}

// B-04方案A：一次性凭据交付令牌展示（新建组织/改绑重置后服务端返回）
const setupTokenDlg = ref(false)
const setupTokenVal = ref('')
const setupTokenLabel = ref('')
const setupTokenPhone = ref('')
const showTokenFallback = ref(false)
function showSetupToken(token: string, label: string, phone: string) {
  setupTokenVal.value = token
  setupTokenLabel.value = label
  setupTokenPhone.value = phone || ''
  showTokenFallback.value = false
  setupTokenDlg.value = true
}
function copySetupToken() {
  navigator.clipboard.writeText(setupTokenVal.value).then(() => {
    ElMessage.success('已复制到剪贴板，请带外告知负责人/成员使用 /auth/setup-password 设密（Token 24h 有效，一次性）')
  }).catch(() => {
    ElMessage.warning('复制失败，请手动选取上方 Token 文本')
  })
}

// 组织管理（平台·org.manage）：新建组织+绑负责人 / 改绑负责人
const oDlg = ref(false); const oForm = ref<any>({ type: 'PROPERTY', name: '', ownerAccount: '', ownerPhone: '' })
async function createOrg() {
  const { data, error } = await api.POST('/orgs', { body: { ...oForm.value } as any })
  if (error) { ElMessage.error('建组织失败：' + ((error as any)?.message ?? '')); return }
  oDlg.value = false; load()
  const token = (data as any)?.ownerSetupToken
  showSetupToken(token || '', '组织已创建，负责人可直接用手机验证码登录', oForm.value.ownerPhone)
}
async function rebindOwner(o: any) {
  try {
    const { ElMessageBox } = await import('element-plus')
    const { value: newPhone } = await ElMessageBox.prompt('新负责人手机（改绑+可选重置交接 US-M1-09）', '改绑负责人 ' + o.name, { inputValidator: (v: string) => /^\d{6,}$/.test(v) || '请输入有效号码' })
    const { data, error } = await api.PATCH('/orgs/{id}/owner', { params: { path: { id: String(o.id) } }, body: { newPhone, resetPassword: true } as any })
    if (error) { ElMessage.error('改绑失败：' + ((error as any)?.message ?? '')); return }
    load()
    const token = (data as any)?.ownerSetupToken
    showSetupToken(token || '', '已改绑负责人，新负责人可直接用手机验证码登录', newPhone)
  } catch { /* 取消 */ }
}

// 停用/启用组织（平台 org.manage · BR-M1-15）：**停新单不断存量**——停用后不能被派新单、
// 不能新建项目/导入批次；但成员照常登录、在催案件照常作业、结算照常。要收回案件另走「批次结项」。
async function toggleOrg(o: any) {
  const disabling = o.status === 'ACTIVE'
  const { ElMessageBox } = await import('element-plus')
  try {
    if (disabling) {
      const { value: reason } = await ElMessageBox.prompt(
        '停用后：不再接受新派单、不能新建项目/导入批次；已承接的在催案件与结算不受影响（要收回案件请走批次结项）。请填写停用原因：',
        '停用组织 ' + o.name,
        { inputValidator: (v: string) => !!(v && v.trim()) || '停用原因必填' },
      )
      const { error } = await api.POST('/orgs/{id}/disable', { params: { path: { id: String(o.id) } }, body: { reason } as any })
      if (error) { ElMessage.error('停用失败：' + ((error as any)?.message ?? '')); return }
      ElMessage.success('已停用（已通知该组织负责人）')
    } else {
      await ElMessageBox.confirm('恢复启用后该组织可正常承接新单、新建项目。', '启用组织 ' + o.name, { type: 'info' })
      const { error } = await api.POST('/orgs/{id}/enable', { params: { path: { id: String(o.id) } } } as any)
      if (error) { ElMessage.error('启用失败：' + ((error as any)?.message ?? '')); return }
      ElMessage.success('已恢复启用')
    }
    load()
  } catch { /* 取消 */ }
}

// 建成员（POST /members · MemberInput）
const cDlg = ref(false)
const cForm = ref<any>({ username: '', name: '', phone: '', role: '', permissions: [] })
function openCreate() {
  cForm.value = { username: '', name: '', phone: '', role: roleOptions.value[0] ?? '', permissions: [] }
  cDlg.value = true
}
async function createMember() {
  const body: any = {
    username: cForm.value.username,
    name: cForm.value.name,
    phone: cForm.value.phone,
    role: cForm.value.role,
    permissions: cForm.value.permissions
  }
  const { error } = await api.POST('/members', { body: body as any })
  if (error) { ElMessage.error('创建失败：' + ((error as any)?.message ?? '')); return }
  cDlg.value = false; load()
  // 有短信后成员直接用手机验证码登录，不必再走「重置密码」发令牌
  showSetupToken('', '成员已创建，可直接用手机验证码登录', cForm.value.phone)
}
// 编辑成员（PATCH /members/{id} · MemberPatch{name?, permissions?, dataScope?}）
const eDlg = ref(false)
const eForm = ref<any>({ id: '', name: '', permissions: [], dataScopeAreas: '', dataScopeProperties: '', dataScopeProviders: '' })
function openEdit(row: any) {
  // dataScope 用逗号分隔字符串作为编辑态，提交时再拆回数组
  const ds = row.dataScope || {}
  eForm.value = {
    id: row.id,
    name: row.name ?? '',
    permissions: row.permissions ? row.permissions.slice() : [],
    dataScopeAreas: (ds.areas || []).join(','),
    dataScopeProperties: (ds.properties || []).join(','),
    dataScopeProviders: (ds.providers || []).join(',')
  }
  eDlg.value = true
}
function splitIds(str: string): string[] {
  return str ? str.split(',').map(function(s: string) { return s.trim() }).filter(function(s: string) { return s.length > 0 }) : []
}
async function submitEdit() {
  const body: any = { name: eForm.value.name, permissions: eForm.value.permissions }
  const areas = splitIds(eForm.value.dataScopeAreas)
  const properties = splitIds(eForm.value.dataScopeProperties)
  const providers = splitIds(eForm.value.dataScopeProviders)
  if (areas.length || properties.length || providers.length) {
    body.dataScope = { areas: areas, properties: properties, providers: providers }
  }
  const { error } = await api.PATCH('/members/{id}' as any, { params: { path: { id: eForm.value.id } }, body: body as any })
  if (error) { ElMessage.error('更新失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已更新成员信息'); eDlg.value = false; load()
}

// 停用/启用
async function toggle(row: any) {
  const op = row.status === 'ACTIVE' ? 'disable' : 'enable'
  const { error } = await api.POST(`/members/{id}/${op}` as any, { params: { path: { id: row.id } } })
  if (error) { ElMessage.error((op === 'disable' ? '停用' : '启用') + '失败：' + ((error as any)?.message ?? '负责人不可停用')); return }
  ElMessage.success(op === 'disable' ? '已停用（私海案件回流公海）' : '已启用'); load()
}
// 重置密码（B-04方案A：不收明文密码——服务端清口令+发一次性 setupToken，展示后带外告知成员）
const pDlg = ref(false)
const pForm = ref<any>({ id: '', name: '', phone: '' })
function openReset(row: any) { pForm.value = { id: row.id, name: row.name, phone: row.phone }; pDlg.value = true }
async function submitReset() {
  const { data, error } = await api.POST('/members/{id}/reset-password', { params: { path: { id: pForm.value.id } }, body: {} as any })
  if (error) { ElMessage.error('重置失败：' + ((error as any)?.message ?? '')); return }
  pDlg.value = false
  const token = (data as any)?.setupToken
  showSetupToken(token || '', '已重置 ' + pForm.value.name + ' 的登录，可用手机验证码重新登录设密码', pForm.value.phone)
}
onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>成员管理</div>
      <div class="ops">
        <span class="note" style="margin:0">
          {{ isPlatform()
            ? 'member.manage · 只管平台员工（SA/SE）；服务商与物业的成员由各自负责人维护 BR-M1-04a'
            : 'member.manage · 仅本组织成员，平台不可跨组织 BR-M1-04a' }}
        </span>
        <button v-if="auth.has('member.manage') && memberTab==='list'" class="btn sm" @click="openCreate">+ 新增成员</button>
      </div>
    </div>

    <!-- 成员列表 / 工作督导 分段（负责人视角·BR-M10-10） -->
    <div v-if="showSupervise" class="segctrl" style="margin-bottom:12px">
      <span :class="{ on: memberTab === 'list' }" @click="memberTab = 'list'">成员列表</span>
      <span :class="{ on: memberTab === 'supervise' }" @click="memberTab = 'supervise'">工作督导</span>
    </div>

    <!-- ══ 工作督导 tab ══ -->
    <template v-if="memberTab === 'supervise'">
      <div class="sec-title">工作督导 — {{ superviseNoun }}</div>
      <div class="note" style="margin-bottom:8px">按成员统计工作量/质量，对异常发起督导（提醒/谈话/培训/记录），记入督导记录留痕（仅督导本组织成员，BR-M10-10）。</div>
      <table>
        <thead><tr><th>成员</th><th>持有案件数</th><th>今日动作</th><th>容量余量</th><th>今日回款</th><th>督导记录</th><th style="width:120px">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in members.filter((m:any)=>!m.isOwner)" :key="row.id">
            <td>{{ row.name }}<span class="mini" style="margin-left:6px;color:var(--sec)">{{ row.username }}</span></td>
            <td class="num">{{ capOf(row.id)?.holding ?? '—' }}</td>
            <td class="num">{{ capOf(row.id)?.todayActions ?? '—' }}</td>
            <td><span v-if="capOf(row.id)" class="tag" :class="(capOf(row.id).remaining<=0)?'dan':(capOf(row.id).remaining<=5?'war':'suc')">余{{ capOf(row.id).remaining }}件</span><span v-else>—</span></td>
            <td class="num">{{ capOf(row.id) ? yuan(capOf(row.id).todayRepayCents) : '—' }}</td>
            <td class="num">{{ supCountOf(row.id) || '—' }}</td>
            <td><button class="btn txt" :disabled="!row.manageable" @click="openSupervise(row)">督导处理</button></td>
          </tr>
          <tr v-if="!members.filter((m:any)=>!m.isOwner).length"><td colspan="7" class="note" style="text-align:center">本组织暂无可督导成员</td></tr>
        </tbody>
      </table>

      <div class="sec-title" style="margin-top:14px">督导记录（GET /members/supervision）</div>
      <table>
        <thead><tr><th>时间</th><th>成员</th><th>方式</th><th>备注</th><th>操作人</th></tr></thead>
        <tbody>
          <tr v-for="r in supervisions" :key="r.id">
            <td class="note">{{ r.createdAt ? String(r.createdAt).slice(0,16).replace('T',' ') : '—' }}</td>
            <td>{{ r.memberName }}</td>
            <td><span class="tag inf">{{ SUP_LABEL[r.action] || r.action }}</span></td>
            <td>{{ r.note || '—' }}</td>
            <td>{{ r.operatorName || '—' }}</td>
          </tr>
          <tr v-if="!supervisions.length"><td colspan="5" class="note" style="text-align:center">暂无督导记录</td></tr>
        </tbody>
      </table>
    </template>

    <!-- ══ 成员列表 tab ══ -->
    <template v-else>
    <table>
      <thead>
        <tr>
          <th>账号</th>
          <th>姓名</th>
          <th>手机</th>
          <th style="width:130px">角色</th>
          <th style="width:130px">状态</th>
          <th v-if="auth.has('member.manage')" style="width:300px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in members" :key="row.id">
          <td>{{ row.username || '—' }}</td>
          <td>{{ row.name || '—' }}</td>
          <td>{{ row.phone || '—' }}</td>
          <td>{{ roleLabel(row.role) }}</td>
          <td>
            <span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'" :title="row.status">{{ statusLabel(row.status) }}</span>
            <span v-if="row.isOwner" class="tag war" style="margin-left:4px">负责人</span>
          </td>
          <td v-if="auth.has('member.manage')">
            <button class="btn txt" :disabled="!row.manageable" @click="openEdit(row)">编辑</button>
            <button class="btn txt" :disabled="row.isOwner || !row.manageable" @click="toggle(row)">{{ row.status==='ACTIVE'?'停用':'启用' }}</button>
            <button class="btn txt" :disabled="!row.manageable" @click="openReset(row)">重置密码</button>
          </td>
        </tr>
        <tr v-if="!members.length">
          <td :colspan="auth.has('member.manage') ? 6 : 5" style="text-align:center;color:var(--sec);padding:32px 0">暂无成员</td>
        </tr>
      </tbody>
    </table>

    <template v-if="isPlatform()">
      <div class="sec-title" style="justify-content:space-between">
        <span style="display:flex;align-items:center;gap:8px">组织管理（GET /orgs · 平台 org.manage）</span>
        <button class="btn txt" @click="oDlg=true">+ 新建组织</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>组织</th>
            <th style="width:110px">类型</th>
            <th style="width:110px">状态</th>
            <th>负责人账号</th>
            <th style="width:180px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in orgs" :key="row.id">
            <td>{{ row.name || '—' }}</td>
            <td><span class="tag" :class="row.type==='PLATFORM' ? 'pri' : (row.type==='PROVIDER' ? 'war' : 'inf')" :title="row.type">{{ orgTypeLabel(row.type) }}</span></td>
            <td><span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'" :title="row.status">{{ statusLabel(row.status) }}</span></td>
            <td>{{ row.ownerAccountId || '—' }}</td>
            <td>
              <button class="btn txt" @click="rebindOwner(row)">改绑负责人</button>
              <button v-if="row.type !== 'PLATFORM'" class="btn txt" :class="{ dgc: row.status === 'ACTIVE' }" @click="toggleOrg(row)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </button>
            </td>
          </tr>
          <tr v-if="!orgs.length">
            <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无组织</td>
          </tr>
        </tbody>
      </table>

      <Pager :page="page" :size="size" :total="total" @update:page="onPage" />
    </template>
    </template>
    <!-- ══ /成员列表 tab ══ -->

    <!-- B-04方案A：一次性凭据交付 Token 展示弹窗（复制按钮+带外告知说明） -->
    <el-dialog v-model="setupTokenDlg" title="账号已创建" width="520px" :close-on-click-modal="false">
      <el-alert type="success" :closable="false" style="margin-bottom:12px" :title="setupTokenLabel" />
      <div style="font-size:14px;line-height:1.9">
        请让本人这样登录（无需复制任何链接）：
        <ol style="margin:6px 0 0;padding-left:20px">
          <li>打开登录页，选 <b>「手机验证码」</b></li>
          <li>输入手机号 <b style="color:var(--primary)">{{ setupTokenPhone || '（开户时填的手机号）' }}</b>，点获取验证码</li>
          <li>填收到的验证码登录，<b>首次登录按提示设置自己的密码</b></li>
        </ol>
      </div>
      <div v-if="setupTokenVal" style="margin-top:14px;border-top:1px dashed var(--bd);padding-top:10px">
        <a class="btn txt" style="padding:0;font-size:12px;color:var(--sec)" @click="showTokenFallback = !showTokenFallback">
          {{ showTokenFallback ? '收起' : '备用：手机收不到验证码？用一次性设密令牌 ▾' }}
        </a>
        <div v-if="showTokenFallback" style="margin-top:8px">
          <el-input :model-value="setupTokenVal" readonly type="textarea" :rows="3"
            style="font-family:monospace;font-size:13px;word-break:break-all" />
          <div style="font-size:12px;color:#999;margin-top:6px">
            此令牌仅展示一次、24h 有效、一次性。带外转交本人，走设密流程后即可登录（首登仍强制改密）。
          </div>
          <el-button size="small" type="primary" plain style="margin-top:8px" @click="copySetupToken">复制令牌</el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="setupTokenDlg=false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 督导处理弹窗（BR-M10-10·POST /members/{id}/supervision-actions） -->
    <DsDrawer v-model="supDlg" :title="`督导处理 · ${supForm.memberName}`" :width="460">
      <div class="note" style="margin-bottom:10px">对成员发起督导并留痕；不直接等同处罚（停权/警告走成员管理的停用/权限）。</div>
      <el-form label-width="80px">
        <el-form-item label="督导方式">
          <el-select v-model="supForm.action" style="width:100%">
            <el-option v-for="a in SUP_ACTIONS" :key="a.v" :label="a.label" :value="a.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="supForm.note" type="textarea" :rows="3" placeholder="督导说明（选填）" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="supDlg=false">取消</el-button><el-button type="primary" @click="submitSupervise">提交并留痕</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="oDlg" title="新建组织+绑负责人" :width="440">
      <el-form label-width="100px">
        <el-form-item label="类型"><el-select v-model="oForm.type"><el-option label="物业" value="PROPERTY" /><el-option label="服务商" value="PROVIDER" /></el-select></el-form-item>
        <el-form-item label="组织名"><el-input v-model="oForm.name" /></el-form-item>
        <el-form-item label="负责人账号"><el-input v-model="oForm.ownerAccount" /></el-form-item>
        <el-form-item label="负责人手机"><el-input v-model="oForm.ownerPhone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="oDlg=false">取消</el-button><el-button type="primary" @click="createOrg">创建</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="cDlg" title="新增成员" :width="480">
      <el-form label-width="90px">
        <el-form-item label="账号"><el-input v-model="cForm.username" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="cForm.name" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="cForm.phone" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="cForm.role">
            <el-option v-for="r in roleOptions" :key="r" :label="roleLabel(r)" :value="r" />
          </el-select>
          <div v-if="roleHint(cForm.role)" style="font-size:12px;color:#999;margin-top:4px">{{ roleHint(cForm.role) }}</div>
        </el-form-item>
        <el-form-item label="权限子集">
          <div style="font-size:12px;color:#999;margin-bottom:4px">勾选可授予的权限（上限为当前主体持有权限）</div>
          <el-checkbox-group v-model="cForm.permissions" style="display:flex;flex-wrap:wrap;gap:4px">
            <el-checkbox v-for="p in myPermissions" :key="p" :label="p" style="margin-right:0" :title="p">{{ permLabel(p) }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="cDlg=false">取消</el-button><el-button type="primary" @click="createMember">创建</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="eDlg" :title="`编辑成员 · ${eForm.name}`" :width="500">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="eForm.name" /></el-form-item>
        <el-form-item label="权限子集">
          <div style="font-size:12px;color:#999;margin-bottom:4px">勾选可授予的权限（上限为当前主体持有权限）</div>
          <el-checkbox-group v-model="eForm.permissions" style="display:flex;flex-wrap:wrap;gap:4px">
            <el-checkbox v-for="p in myPermissions" :key="p" :label="p" style="margin-right:0" :title="p">{{ permLabel(p) }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="isPlatform()" label="数据范围">
          <div style="font-size:12px;color:#999;margin-bottom:4px">dataScope（PATCH，ID 逗号分隔，留空表示不限制）</div>
          <el-form-item label="小区 areas" label-width="100px"><el-input v-model="eForm.dataScopeAreas" placeholder="area-id,... 留空=全部" /></el-form-item>
          <el-form-item label="物业 properties" label-width="110px"><el-input v-model="eForm.dataScopeProperties" placeholder="org-id,... 留空=全部" /></el-form-item>
          <el-form-item label="服务商 providers" label-width="120px"><el-input v-model="eForm.dataScopeProviders" placeholder="org-id,... 留空=全部" /></el-form-item>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="eDlg=false">取消</el-button><el-button type="primary" @click="submitEdit">保存</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="pDlg" :title="`重置密码 · ${pForm.name}`" :width="420">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="重置将清除现有口令并生成一次性凭据 Token（24h 有效，一次性），请在下一步弹窗中复制并带外告知成员。成员用此 Token 走 /auth/setup-password 自设密码后方可登录（不支持管理员代设明文密码）。" />
      <template #footer><el-button @click="pDlg=false">取消</el-button><el-button type="primary" @click="submitReset">重置并获取 Token</el-button></template>
    </DsDrawer>

  </div>
</template>
