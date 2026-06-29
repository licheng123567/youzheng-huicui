<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 组织管理（平台·org.manage）：组织目录 + 新建组织(绑唯一负责人) + 改绑负责人。
// 自包含视图，挂 /org-mgmt；不依赖 router/main.ts/AppLayout 改动。

const orgs = ref<any[]>([])

const typeTag = (t: string) => t === 'PLATFORM' ? 'pri' : (t === 'PROVIDER' ? 'war' : 'inf')
const typeLabel = (t: string) => t === 'PLATFORM' ? '平台' : (t === 'PROVIDER' ? '服务商' : (t === 'PROPERTY' ? '物业' : (t || '—')))

async function load() {
  const { data } = await api.GET('/orgs', { params: { query: { page: 1, size: 50 } } as any })
  orgs.value = (data as any)?.items ?? []
}

// B-04方案A：一次性凭据交付令牌展示（契约未必返回，容错处理）
const setupTokenDlg = ref(false)
const setupTokenVal = ref('')
const setupTokenLabel = ref('')
function showSetupToken(token: string, label: string) {
  setupTokenVal.value = token
  setupTokenLabel.value = label
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
  if (token) {
    showSetupToken(token, '负责人初始凭据（带外转交，24h 有效，一次性）')
  } else {
    ElMessage.success('已建组织 + 绑负责人')
  }
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
    if (token) {
      showSetupToken(token, '负责人重置凭据（带外转交，24h 有效，一次性）')
    } else {
      ElMessage.success('已改绑负责人')
    }
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
          <td><span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'">{{ row.status || '—' }}</span></td>
          <td><button class="btn txt" @click="rebindOwner(row)">改绑负责人</button></td>
        </tr>
        <tr v-if="!orgs.length">
          <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无组织</td>
        </tr>
      </tbody>
    </table>

    <!-- 新建组织（POST /orgs · OrgInput） -->
    <el-dialog v-model="oDlg" title="新建组织 + 绑负责人（POST /orgs · org.manage）" width="440px">
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
    </el-dialog>

    <!-- B-04方案A：一次性凭据交付 Token 展示弹窗（容错；契约未必返回） -->
    <el-dialog v-model="setupTokenDlg" title="一次性凭据 Token（带外转交）" width="500px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
        :title="setupTokenLabel + ' — 此 Token 仅展示一次，关闭后不可再查，请立即复制并带外告知。'" />
      <el-input :model-value="setupTokenVal" readonly type="textarea" :rows="3"
        style="font-family:monospace;font-size:13px;word-break:break-all" />
      <div style="font-size:12px;color:#999;margin-top:6px">
        收到 Token 的负责人须访问 <b>POST /auth/setup-password</b>（{token, newPassword}）设置初始密码后方可登录；首次登录后强制改密。
      </div>
      <template #footer>
        <el-button type="primary" @click="copySetupToken">复制 Token</el-button>
        <el-button @click="setupTokenDlg=false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
