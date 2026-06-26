<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

// 个人中心：资料查看(GET /me 已有) + 自助改密(POST /me/password · v1.3.0)。
const auth = useAuth()
const me = computed(() => auth.me)
const dlg = ref(false)
const form = ref({ oldPassword: '', newPassword: '', confirm: '' })

function open() { form.value = { oldPassword: '', newPassword: '', confirm: '' }; dlg.value = true }
async function submit() {
  if (form.value.newPassword.length < 6) { ElMessage.warning('新密码至少 6 位'); return }
  if (form.value.newPassword !== form.value.confirm) { ElMessage.warning('两次新密码不一致'); return }
  const { error } = await api.POST('/me/password', { body: { oldPassword: form.value.oldPassword, newPassword: form.value.newPassword } as any })
  if (error) { ElMessage.error('改密失败：' + ((error as any)?.message ?? '旧密码错误')); return }
  ElMessage.success('密码已修改'); dlg.value = false
}
</script>

<template>
  <el-card v-if="me" header="个人中心">
    <el-descriptions :column="2" border>
      <el-descriptions-item label="账号 ID">{{ me.accountId }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ me.name }}</el-descriptions-item>
      <el-descriptions-item label="角色">{{ me.role }}</el-descriptions-item>
      <el-descriptions-item label="组织">{{ me.org?.name }}（{{ me.org?.type }}）</el-descriptions-item>
      <el-descriptions-item label="数据范围" :span="2">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量' }}</el-descriptions-item>
      <el-descriptions-item label="权限点" :span="2"><el-tag v-for="p in me.permissions" :key="p" style="margin:2px">{{ p }}</el-tag></el-descriptions-item>
    </el-descriptions>
    <div style="margin-top:14px">
      <el-button type="primary" @click="open">修改密码</el-button>
      <span style="color:#909399;font-size:12px;margin-left:8px">手机改绑由管理员/负责人交接（BR-M1-02）</span>
    </div>

    <el-dialog v-model="dlg" title="修改密码（自助 · 校验旧密码）" width="400px">
      <el-form label-width="90px">
        <el-form-item label="旧密码"><el-input v-model="form.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password placeholder="至少 6 位" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="submit">确认修改</el-button></template>
    </el-dialog>
  </el-card>
</template>
