<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 成员管理/督导(M1/M10·member.manage)：本组织成员 CRUD/停用启用/重置密码 + 督导记录。
const auth = useAuth()
const members = ref<any[]>([])
const sup = ref<any[]>([])
const orgs = ref<any[]>([])
const isPlatform = () => auth.has('org.manage')

// BR-M1-04a：角色下拉按当前组织类型过滤
const ROLE_LABELS: Record<string, string> = { SA: 'SA 平台管理员', SE: 'SE 平台执行', PL: 'PL 物业负责人', PC: 'PC 物业催收员', VL: 'VL 服务商负责人', CO: 'CO 服务商催收员' }
const roleOptions = computed<string[]>(() => {
  const orgType: string = (auth.me as any)?.org?.type ?? ''
  if (orgType === 'PLATFORM') return ['SA', 'SE']
  if (orgType === 'PROPERTY') return ['PL', 'PC']
  if (orgType === 'PROVIDER') return ['VL', 'CO']
  return ['PL', 'PC'] // 保守兜底
})

// 当前主体可授予的权限上限（Me.permissions）
const myPermissions = computed<string[]>(() => (auth.me as any)?.permissions ?? [])

async function load() {
  const m = await api.GET('/members', { params: { query: { page: 1, size: 50 } } as any })
  members.value = (m.data as any)?.items ?? []
  const s = await api.GET('/members/supervision', { params: { query: { page: 1, size: 30 } } as any })
  sup.value = (s.data as any)?.items ?? []
  if (isPlatform()) orgs.value = ((await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
}

// 组织管理（平台·org.manage）：新建组织+绑负责人 / 改绑负责人
const oDlg = ref(false); const oForm = ref<any>({ type: 'PROPERTY', name: '', ownerAccount: '', ownerPhone: '' })
async function createOrg() {
  const { error } = await api.POST('/orgs', { body: { ...oForm.value } as any })
  if (error) { ElMessage.error('建组织失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已建组织+绑负责人（初始随机口令，请重置告知）'); oDlg.value = false; load()
}
async function rebindOwner(o: any) {
  try {
    const { ElMessageBox } = await import('element-plus')
    const { value: newPhone } = await ElMessageBox.prompt('新负责人手机（改绑+可选重置交接 US-M1-09）', '改绑负责人 ' + o.name, { inputValidator: (v: string) => /^\d{6,}$/.test(v) || '请输入有效号码' })
    const { error } = await api.PATCH('/orgs/{id}/owner', { params: { path: { id: String(o.id) } }, body: { newPhone, resetPassword: true } as any })
    if (error) { ElMessage.error('改绑失败：' + ((error as any)?.message ?? '')); return }
    ElMessage.success('已改绑负责人'); load()
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
  ElMessage.success('已创建（默认随机初始口令，请用「重置密码」设定并告知）'); cDlg.value = false; load()
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
// 重置密码（H-2 后响应不回传明文，故管理员显式设定 newPassword 才知道）
const pDlg = ref(false)
const pForm = ref<any>({ id: '', name: '', newPassword: '', notify: false })
function openReset(row: any) { pForm.value = { id: row.id, name: row.name, newPassword: '', notify: false }; pDlg.value = true }
async function submitReset() {
  const { error } = await api.POST('/members/{id}/reset-password', { params: { path: { id: pForm.value.id } }, body: { newPassword: pForm.value.newPassword || null, notify: pForm.value.notify } as any })
  if (error) { ElMessage.error('重置失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(pForm.value.newPassword ? '已重置为指定口令' : '已重置为随机口令（需短信通知员工）'); pDlg.value = false
}
// 督导
const sDlg = ref(false)
const sForm = ref<any>({ id: '', name: '', action: 'REMIND', note: '' })
function openSup(row: any) { sForm.value = { id: row.id, name: row.name, action: 'REMIND', note: '' }; sDlg.value = true }
async function submitSup() {
  const { error } = await api.POST('/members/{id}/supervision-actions', { params: { path: { id: sForm.value.id } }, body: { action: sForm.value.action, note: sForm.value.note } as any })
  if (error) { ElMessage.error('督导记录失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('督导已记录'); sDlg.value = false; load()
}
onMounted(load)
</script>

<template>
  <el-card header="成员管理 / 督导（member.manage · 仅本组织成员，平台不可跨组织 BR-M1-04a）">
    <el-button v-if="auth.has('member.manage')" type="primary" size="small" style="margin-bottom:10px" @click="openCreate">新增成员</el-button>
    <el-table :data="members" border size="small">
      <el-table-column prop="username" label="账号" /><el-table-column prop="name" label="姓名" />
      <el-table-column prop="phone" label="手机" /><el-table-column prop="role" label="角色" width="80" />
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{ row.status }}</el-tag><el-tag v-if="row.isOwner" size="small" type="warning" style="margin-left:4px">负责人</el-tag></template></el-table-column>
      <el-table-column v-if="auth.has('member.manage')" label="操作" width="300">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="!row.manageable" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" :disabled="row.isOwner || !row.manageable" @click="toggle(row)">{{ row.status==='ACTIVE'?'停用':'启用' }}</el-button>
          <el-button size="small" :disabled="!row.manageable" @click="openReset(row)">重置密码</el-button>
          <el-button size="small" type="primary" text :disabled="!row.manageable" @click="openSup(row)">督导</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider content-position="left">督导记录（GET /members/supervision · 写审计 BR-M10-10）</el-divider>
    <el-table :data="sup" border size="small">
      <el-table-column prop="memberName" label="成员" /><el-table-column prop="action" label="动作" width="100" />
      <el-table-column prop="note" label="说明" /><el-table-column prop="tm" label="时间" />
    </el-table>

    <template v-if="isPlatform()">
      <el-divider content-position="left">组织管理（GET /orgs · 平台 org.manage）<el-button size="small" text type="primary" @click="oDlg=true">+ 新建组织</el-button></el-divider>
      <el-table :data="orgs" border size="small">
        <el-table-column prop="name" label="组织" /><el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="status" label="状态" width="90" /><el-table-column prop="ownerAccountId" label="负责人账号" />
        <el-table-column label="操作" width="110"><template #default="{row}"><el-button size="small" @click="rebindOwner(row)">改绑负责人</el-button></template></el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="oDlg" title="新建组织+绑负责人（POST /orgs · org.manage）" width="440px">
      <el-form label-width="100px">
        <el-form-item label="类型"><el-select v-model="oForm.type"><el-option label="物业" value="PROPERTY" /><el-option label="服务商" value="PROVIDER" /></el-select></el-form-item>
        <el-form-item label="组织名"><el-input v-model="oForm.name" /></el-form-item>
        <el-form-item label="负责人账号"><el-input v-model="oForm.ownerAccount" /></el-form-item>
        <el-form-item label="负责人手机"><el-input v-model="oForm.ownerPhone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="oDlg=false">取消</el-button><el-button type="primary" @click="createOrg">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="cDlg" title="新增成员（POST /members · 本组织 · BR-M1-04a）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="账号"><el-input v-model="cForm.username" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="cForm.name" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="cForm.phone" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="cForm.role">
            <el-option v-for="r in roleOptions" :key="r" :label="ROLE_LABELS[r] || r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限子集">
          <div style="font-size:12px;color:#999;margin-bottom:4px">勾选可授予的权限（上限为当前主体持有权限）</div>
          <el-checkbox-group v-model="cForm.permissions" style="display:flex;flex-wrap:wrap;gap:4px">
            <el-checkbox v-for="p in myPermissions" :key="p" :label="p" style="margin-right:0">{{ p }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="cDlg=false">取消</el-button><el-button type="primary" @click="createMember">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="eDlg" :title="`编辑成员 · ${eForm.name}（PATCH /members/{id}）`" width="500px">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="eForm.name" /></el-form-item>
        <el-form-item label="权限子集">
          <div style="font-size:12px;color:#999;margin-bottom:4px">勾选可授予的权限（上限为当前主体持有权限）</div>
          <el-checkbox-group v-model="eForm.permissions" style="display:flex;flex-wrap:wrap;gap:4px">
            <el-checkbox v-for="p in myPermissions" :key="p" :label="p" style="margin-right:0">{{ p }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="数据范围">
          <div style="font-size:12px;color:#999;margin-bottom:4px">dataScope（PATCH，ID 逗号分隔，留空表示不限制）</div>
          <el-form-item label="小区 areas" label-width="100px"><el-input v-model="eForm.dataScopeAreas" placeholder="area-id,... 留空=全部" /></el-form-item>
          <el-form-item label="物业 properties" label-width="110px"><el-input v-model="eForm.dataScopeProperties" placeholder="org-id,... 留空=全部" /></el-form-item>
          <el-form-item label="服务商 providers" label-width="120px"><el-input v-model="eForm.dataScopeProviders" placeholder="org-id,... 留空=全部" /></el-form-item>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="eDlg=false">取消</el-button><el-button type="primary" @click="submitEdit">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="pDlg" :title="`重置密码 · ${pForm.name}`" width="420px">
      <el-alert type="info" :closable="false" style="margin-bottom:10px" title="出于安全，系统不回显口令。请在此设定新口令并自行告知员工；留空则随机生成（需短信通知）。" />
      <el-form label-width="90px">
        <el-form-item label="新口令"><el-input v-model="pForm.newPassword" placeholder="留空=随机" show-password /></el-form-item>
        <el-form-item label="短信通知"><el-switch v-model="pForm.notify" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="pDlg=false">取消</el-button><el-button type="primary" @click="submitReset">重置</el-button></template>
    </el-dialog>

    <el-dialog v-model="sDlg" :title="`督导 · ${sForm.name}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="动作"><el-select v-model="sForm.action"><el-option label="提醒 REMIND" value="REMIND" /><el-option label="谈话 TALK" value="TALK" /><el-option label="培训 TRAINING" value="TRAINING" /><el-option label="记录 NOTE" value="NOTE" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="sForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sDlg=false">取消</el-button><el-button type="primary" @click="submitSup">记录</el-button></template>
    </el-dialog>
  </el-card>
</template>
