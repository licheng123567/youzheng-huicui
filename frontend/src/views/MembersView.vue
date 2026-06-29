<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { roleLabel, roleHint } from '../constants/roles'

// 成员管理/督导(M1/M10·member.manage)：本组织成员 CRUD/停用启用/重置密码 + 督导记录。
const auth = useAuth()
const members = ref<any[]>([])
const sup = ref<any[]>([])
const orgs = ref<any[]>([])
const isPlatform = () => auth.has('org.manage')

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
  const m = await api.GET('/members', { params: { query: { page: 1, size: 50 } } as any })
  members.value = (m.data as any)?.items ?? []
  const s = await api.GET('/members/supervision', { params: { query: { page: 1, size: 30 } } as any })
  sup.value = (s.data as any)?.items ?? []
  if (isPlatform()) orgs.value = ((await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })).data as any)?.items ?? []
}

// B-04方案A：一次性凭据交付令牌展示（新建组织/改绑重置后服务端返回）
const setupTokenDlg = ref(false)
const setupTokenVal = ref('')
const setupTokenLabel = ref('')
function showSetupToken(token: string, label: string) {
  setupTokenVal.value = token
  setupTokenLabel.value = label
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
  // B-04方案A：展示一次性 setupToken，平台须带外告知 owner
  const token = (data as any)?.ownerSetupToken
  if (token) {
    showSetupToken(token, '负责人初始凭据（带外转交，24h 有效，一次性）')
  } else {
    ElMessage.success('已建组织+绑负责人')
  }
}
async function rebindOwner(o: any) {
  try {
    const { ElMessageBox } = await import('element-plus')
    const { value: newPhone } = await ElMessageBox.prompt('新负责人手机（改绑+可选重置交接 US-M1-09）', '改绑负责人 ' + o.name, { inputValidator: (v: string) => /^\d{6,}$/.test(v) || '请输入有效号码' })
    const { data, error } = await api.PATCH('/orgs/{id}/owner', { params: { path: { id: String(o.id) } }, body: { newPhone, resetPassword: true } as any })
    if (error) { ElMessage.error('改绑失败：' + ((error as any)?.message ?? '')); return }
    load()
    // B-04方案A：展示一次性 setupToken
    const token = (data as any)?.ownerSetupToken
    if (token) {
      showSetupToken(token, '负责人重置凭据（带外转交，24h 有效，一次性）')
    } else {
      ElMessage.success('已改绑负责人')
    }
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
  ElMessage.success('已创建（须用「重置密码」发放一次性凭据告知成员）'); cDlg.value = false; load()
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
// 重置密码（B-04方案A：响应返回 setupToken，展示后带外告知成员）
const pDlg = ref(false)
const pForm = ref<any>({ id: '', name: '', newPassword: '', notify: false })
function openReset(row: any) { pForm.value = { id: row.id, name: row.name, newPassword: '', notify: false }; pDlg.value = true }
async function submitReset() {
  // ResetPasswordInput{newPassword?:string|null（留空=服务端生成）, notify?:boolean（短信通知员工）}
  const body: any = {}
  if (pForm.value.newPassword) body.newPassword = pForm.value.newPassword
  if (pForm.value.notify) body.notify = true
  const { data, error } = await api.POST('/members/{id}/reset-password', { params: { path: { id: pForm.value.id } }, body: body as any })
  if (error) { ElMessage.error('重置失败：' + ((error as any)?.message ?? '')); return }
  pDlg.value = false
  // B-04方案A：展示一次性 setupToken
  const token = (data as any)?.setupToken
  if (token) {
    showSetupToken(token, '成员凭据（带外告知 ' + pForm.value.name + '，24h 有效，一次性）')
  } else {
    ElMessage.success('已重置凭据')
  }
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
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>成员管理 / 督导</div>
      <div class="ops">
        <span class="note" style="margin:0">member.manage · 仅本组织成员，平台不可跨组织 BR-M1-04a</span>
        <button v-if="auth.has('member.manage')" class="btn sm" @click="openCreate">+ 新增成员</button>
      </div>
    </div>

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
            <span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'">{{ row.status }}</span>
            <span v-if="row.isOwner" class="tag war" style="margin-left:4px">负责人</span>
          </td>
          <td v-if="auth.has('member.manage')">
            <button class="btn txt" :disabled="!row.manageable" @click="openEdit(row)">编辑</button>
            <button class="btn txt" :disabled="row.isOwner || !row.manageable" @click="toggle(row)">{{ row.status==='ACTIVE'?'停用':'启用' }}</button>
            <button class="btn txt" :disabled="!row.manageable" @click="openReset(row)">重置密码</button>
            <button class="btn txt" :disabled="!row.manageable" @click="openSup(row)">督导</button>
          </td>
        </tr>
        <tr v-if="!members.length">
          <td :colspan="auth.has('member.manage') ? 6 : 5" style="text-align:center;color:var(--sec);padding:32px 0">暂无成员</td>
        </tr>
      </tbody>
    </table>

    <div class="sec-title">督导记录（GET /members/supervision · 写审计 BR-M10-10）</div>
    <table>
      <thead>
        <tr>
          <th>成员</th>
          <th style="width:110px">动作</th>
          <th>说明</th>
          <th style="width:180px">时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in sup" :key="i">
          <td>{{ row.memberName || '—' }}</td>
          <td><span class="tag inf">{{ row.action }}</span></td>
          <td>{{ row.note || '—' }}</td>
          <td>{{ row.tm || '—' }}</td>
        </tr>
        <tr v-if="!sup.length">
          <td colspan="4" style="text-align:center;color:var(--sec);padding:32px 0">暂无督导记录</td>
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
            <th style="width:120px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in orgs" :key="row.id">
            <td>{{ row.name || '—' }}</td>
            <td><span class="tag" :class="row.type==='PLATFORM' ? 'pri' : (row.type==='PROVIDER' ? 'war' : 'inf')">{{ row.type }}</span></td>
            <td><span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'">{{ row.status }}</span></td>
            <td>{{ row.ownerAccountId || '—' }}</td>
            <td><button class="btn txt" @click="rebindOwner(row)">改绑负责人</button></td>
          </tr>
          <tr v-if="!orgs.length">
            <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无组织</td>
          </tr>
        </tbody>
      </table>
    </template>

    <!-- B-04方案A：一次性凭据交付 Token 展示弹窗（复制按钮+带外告知说明） -->
    <el-dialog v-model="setupTokenDlg" title="一次性凭据 Token（带外转交）" width="500px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
        :title="setupTokenLabel + ' — 此 Token 仅展示一次，关闭后不可再查，请立即复制并带外告知。'" />
      <el-input :model-value="setupTokenVal" readonly type="textarea" :rows="3"
        style="font-family:monospace;font-size:13px;word-break:break-all" />
      <div style="font-size:12px;color:#999;margin-top:6px">
        收到 Token 的负责人/成员须访问 <b>POST /auth/setup-password</b>（{token, newPassword}）设置初始密码后方可登录；首次登录后强制改密。
      </div>
      <template #footer>
        <el-button type="primary" @click="copySetupToken">复制 Token</el-button>
        <el-button @click="setupTokenDlg=false">关闭</el-button>
      </template>
    </el-dialog>

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
            <el-option v-for="r in roleOptions" :key="r" :label="roleLabel(r)" :value="r" />
          </el-select>
          <div v-if="roleHint(cForm.role)" style="font-size:12px;color:#999;margin-top:4px">{{ roleHint(cForm.role) }}</div>
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

    <el-dialog v-model="pDlg" :title="`重置密码 · ${pForm.name}（B-04方案A·一次性凭据）`" width="420px">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        title="重置后系统将生成一次性凭据 Token（24h 有效，一次性），请在下一步弹窗中复制并带外告知成员。成员用此 Token 走 /auth/setup-password 设密后方可登录。" />
      <el-form label-width="100px">
        <el-form-item label="自定义新密码">
          <el-input v-model="pForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="留空=系统生成" />
          <div style="font-size:12px;color:#999;margin-top:4px">留空则由系统生成临时密码（仍以一次性凭据形式发放）</div>
        </el-form-item>
        <el-form-item label="短信通知">
          <el-checkbox v-model="pForm.notify">短信通知员工（notify）</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="pDlg=false">取消</el-button><el-button type="primary" @click="submitReset">重置并获取 Token</el-button></template>
    </el-dialog>

    <el-dialog v-model="sDlg" :title="`督导 · ${sForm.name}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="动作"><el-select v-model="sForm.action"><el-option label="提醒 REMIND" value="REMIND" /><el-option label="谈话 TALK" value="TALK" /><el-option label="培训 TRAINING" value="TRAINING" /><el-option label="记录 NOTE" value="NOTE" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="sForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sDlg=false">取消</el-button><el-button type="primary" @click="submitSup">记录</el-button></template>
    </el-dialog>
  </div>
</template>
