<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'
import { statusLabel } from '../constants/enums'
import DsDrawer from '../components/DsDrawer.vue'

// 组织管理（平台·org.manage）：组织目录 + 新建组织(绑唯一负责人) + 改绑负责人。
// 自包含视图，挂 /org-mgmt；不依赖 router/main.ts/AppLayout 改动。

const orgs = ref<any[]>([])
// 分页：此前硬编码 page:1 且无分页控件 —— 超出一页的数据静默消失，用户无从得知。
const page = ref(1)
const size = ref(50)
const total = ref(0)
function onPage(p: number) { page.value = p; load() }


const typeTag = (t: string) => t === 'PLATFORM' ? 'pri' : (t === 'PROVIDER' ? 'war' : 'inf')
const typeLabel = (t: string) => t === 'PLATFORM' ? '平台' : (t === 'PROVIDER' ? '服务商' : (t === 'PROPERTY' ? '物业' : (t || '—')))

async function load() {
  const { data } = await api.GET('/orgs', { params: { query: { page: page.value, size: size.value } } as any })
  orgs.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

// B-04方案A：一次性凭据交付令牌展示（契约未必返回，容错处理）
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
  navigator.clipboard.writeText(setupTokenVal.value).then(function () {
    ElMessage.success('已复制到剪贴板，请带外告知负责人使用 /auth/setup-password 设密（24h 有效，一次性）')
  }).catch(function () {
    ElMessage.warning('复制失败，请手动选取上方 Token 文本')
  })
}

// 新建组织（POST /orgs · OrgInput{type,name,ownerAccount,ownerPhone}）
const oDlg = ref(false)
const oForm = ref<any>({ type: 'PROPERTY', name: '', ownerAccount: '', ownerPhone: '' })
function openCreate() {
  oForm.value = { type: 'PROPERTY', name: '', ownerAccount: '', ownerPhone: '' }
  oDlg.value = true
}
async function createOrg() {
  if (!oForm.value.name || !oForm.value.ownerAccount || !oForm.value.ownerPhone) {
    ElMessage.warning('组织名 / 负责人账号 / 负责人手机为必填')
    return
  }
  const { data, error } = await api.POST('/orgs', { body: { ...oForm.value } as any })
  if (error) { ElMessage.error('建组织失败：' + ((error as any)?.message ?? '')); return }
  oDlg.value = false
  load()
  // 若响应带一次性凭据则容错展示（契约未必有该字段）
  const token = (data as any)?.ownerSetupToken
  showSetupToken(token || '', '组织已创建，负责人可直接用手机验证码登录', oForm.value.ownerPhone)
}

// 改绑负责人（PATCH /orgs/{id}/owner · {newPhone, resetPassword:true}）
async function rebindOwner(o: any) {
  try {
    const { ElMessageBox } = await import('element-plus')
    const { value: newPhone } = await ElMessageBox.prompt(
      '新负责人手机（改绑 + 重置交接 US-M1-09）',
      '改绑负责人 ' + (o.name || ''),
      { inputValidator: function (v: string) { return /^\d{6,}$/.test(v) || '请输入有效号码' } }
    )
    const { data, error } = await api.PATCH('/orgs/{id}/owner', {
      params: { path: { id: String(o.id) } },
      body: { newPhone, resetPassword: true } as any
    })
    if (error) { ElMessage.error('改绑失败：' + ((error as any)?.message ?? '')); return }
    load()
    const token = (data as any)?.ownerSetupToken
    showSetupToken(token || '', '已改绑负责人，新负责人可直接用手机验证码登录', newPhone)
  } catch { /* 取消 */ }
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>组织管理</div>
      <div class="ops">
        <span class="note" style="margin:0">org.manage · 平台全量；新建组织绑唯一负责人 BR-M1-01</span>
        <button class="btn sm" @click="openCreate">+ 新建组织</button>
      </div>
    </div>

    <table>
      <thead>
        <tr>
          <th style="width:110px">类型</th>
          <th>名称</th>
          <th>负责人账号</th>
          <th style="width:110px">状态</th>
          <th style="width:130px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in orgs" :key="row.id">
          <td><span class="tag" :class="typeTag(row.type)">{{ typeLabel(row.type) }}</span></td>
          <td>{{ row.name || '—' }}</td>
          <td>{{ row.ownerAccountId || '—' }}</td>
          <td><span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'">{{ statusLabel(row.status) }}</span></td>
          <td><button class="btn txt" @click="rebindOwner(row)">改绑负责人</button></td>
        </tr>
        <tr v-if="!orgs.length">
          <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无组织</td>
        </tr>
      </tbody>
    </table>

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />

    <!-- 新建组织（POST /orgs · OrgInput） -->
    <DsDrawer v-model="oDlg" title="新建组织 + 绑负责人" :width="440">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="oForm.type">
            <el-option label="物业" value="PROPERTY" />
            <el-option label="服务商" value="PROVIDER" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织名"><el-input v-model="oForm.name" /></el-form-item>
        <el-form-item label="负责人账号"><el-input v-model="oForm.ownerAccount" /></el-form-item>
        <el-form-item label="负责人手机"><el-input v-model="oForm.ownerPhone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="oDlg=false">取消</el-button>
        <el-button type="primary" @click="createOrg">创建</el-button>
      </template>
    </DsDrawer>

    <!-- 开户成功：主推「手机验证码登录」，一次性 Token 降级为备用（个别号码收不到码时用） -->
    <el-dialog v-model="setupTokenDlg" title="账号已创建" width="520px" :close-on-click-modal="false">
      <el-alert type="success" :closable="false" style="margin-bottom:12px" :title="setupTokenLabel" />
      <div style="font-size:14px;line-height:1.9">
        请让负责人这样登录（无需复制任何链接）：
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
            此令牌仅展示一次、24h 有效、一次性。带外转交负责人，走设密流程后即可登录（首登仍强制改密）。
          </div>
          <el-button size="small" type="primary" plain style="margin-top:8px" @click="copySetupToken">复制令牌</el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="setupTokenDlg=false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>
