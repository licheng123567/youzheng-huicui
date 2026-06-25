<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// 成员管理/督导(M1/M10·member.manage)：本组织成员 CRUD/停用启用/重置密码 + 督导记录。
const auth = useAuth()
const members = ref<any[]>([])
const sup = ref<any[]>([])

async function load() {
  const m = await api.GET('/members', { params: { query: { page: 1, size: 50 } } as any })
  members.value = (m.data as any)?.items ?? []
  const s = await api.GET('/members/supervision', { params: { query: { page: 1, size: 30 } } as any })
  sup.value = (s.data as any)?.items ?? []
}

// 建成员
const cDlg = ref(false)
const cForm = ref<any>({ username: '', name: '', phone: '', role: 'PC' })
async function createMember() {
  const { error } = await api.POST('/members', { body: { ...cForm.value } as any })
  if (error) { ElMessage.error('创建失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已创建（默认随机初始口令，请用「重置密码」设定并告知）'); cDlg.value = false; load()
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
    <el-button v-if="auth.has('member.manage')" type="primary" size="small" style="margin-bottom:10px" @click="cDlg=true">新增成员</el-button>
    <el-table :data="members" border size="small">
      <el-table-column prop="username" label="账号" /><el-table-column prop="name" label="姓名" />
      <el-table-column prop="phone" label="手机" /><el-table-column prop="role" label="角色" width="80" />
      <el-table-column label="状态" width="90"><template #default="{row}"><el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{ row.status }}</el-tag><el-tag v-if="row.isOwner" size="small" type="warning" style="margin-left:4px">负责人</el-tag></template></el-table-column>
      <el-table-column v-if="auth.has('member.manage')" label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" :disabled="row.isOwner" @click="toggle(row)">{{ row.status==='ACTIVE'?'停用':'启用' }}</el-button>
          <el-button size="small" @click="openReset(row)">重置密码</el-button>
          <el-button size="small" type="primary" text @click="openSup(row)">督导</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider content-position="left">督导记录（GET /members/supervision · 写审计 BR-M10-10）</el-divider>
    <el-table :data="sup" border size="small">
      <el-table-column prop="memberName" label="成员" /><el-table-column prop="action" label="动作" width="100" />
      <el-table-column prop="note" label="说明" /><el-table-column prop="tm" label="时间" />
    </el-table>

    <el-dialog v-model="cDlg" title="新增成员（POST /members · 本组织）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="账号"><el-input v-model="cForm.username" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="cForm.name" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="cForm.phone" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="cForm.role"><el-option v-for="r in ['PC','CO','PL','VL']" :key="r" :label="r" :value="r" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="cDlg=false">取消</el-button><el-button type="primary" @click="createMember">创建</el-button></template>
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
