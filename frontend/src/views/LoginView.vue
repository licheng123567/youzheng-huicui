<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const username = ref('admin')
const password = ref('Admin@123')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push((route.query.redirect as string) || '/')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div style="height:100vh;display:flex;align-items:center;justify-content:center;background:#f0f2f5">
    <el-card style="width:360px">
      <h2 style="text-align:center;margin:0 0 16px">有证慧催 登录</h2>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名（admin / cuihu_pl）" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="口令（Admin@123）" show-password />
        </el-form-item>
        <el-button type="primary" style="width:100%" :loading="loading" @click="submit">登录</el-button>
      </el-form>
      <p style="color:#909399;font-size:12px;margin-top:12px">
        地基期 dev 账号：admin（平台 SA）/ cuihu_pl（翠湖物业 PL）。登录走契约 <code>/auth/login</code>。
      </p>
    </el-card>
  </div>
</template>
