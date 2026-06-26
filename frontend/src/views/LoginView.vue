<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const mode = ref<'password' | 'sms'>('password')
const username = ref('admin'); const password = ref('Admin@123')
const phone = ref('13900009000'); const code = ref('')
const loading = ref(false); const smsSent = ref(false)
// 多账号选择态
const accounts = ref<any[]>([]); const loginTicket = ref('')

function done() { router.push((route.query.redirect as string) || '/') }

async function submit() {
  loading.value = true
  try {
    const r = mode.value === 'password'
      ? await auth.login(username.value, password.value)
      : await auth.loginSms(phone.value, code.value)
    if (r.done) { done(); return }
    loginTicket.value = r.loginTicket!; accounts.value = r.accounts ?? []   // 一号多账号 → 选择
  } catch (e) { ElMessage.error((e as Error).message) } finally { loading.value = false }
}
async function sendCode() {
  try { await auth.requestSmsCode(phone.value); smsSent.value = true; ElMessage.success('验证码已发送') }
  catch (e) { ElMessage.error((e as Error).message) }
}
async function pick(a: any) {
  loading.value = true
  try { await auth.selectAccount(loginTicket.value, a.accountId); done() }
  catch (e) { ElMessage.error((e as Error).message); accounts.value = [] } finally { loading.value = false }
}
</script>

<template>
  <div style="height:100vh;display:flex;align-items:center;justify-content:center;background:#f0f2f5">
    <el-card style="width:380px">
      <h2 style="text-align:center;margin:0 0 16px">有证慧催 登录</h2>

      <!-- 多账号选择 -->
      <template v-if="accounts.length">
        <el-alert type="info" :closable="false" title="该手机关联多个账号，请选择登录身份（一号多账号 BR-M1-11）" style="margin-bottom:12px" />
        <el-button v-for="a in accounts" :key="a.accountId" style="width:100%;margin:4px 0;justify-content:flex-start" :loading="loading" @click="pick(a)">
          {{ a.name }} · {{ a.role }} · {{ a.orgName }}
        </el-button>
        <el-button text size="small" @click="accounts = []">返回重新登录</el-button>
      </template>

      <!-- 登录表单 -->
      <template v-else>
        <el-radio-group v-model="mode" style="margin-bottom:14px"><el-radio-button label="password">口令登录</el-radio-button><el-radio-button label="sms">短信登录</el-radio-button></el-radio-group>
        <el-form @submit.prevent="submit">
          <template v-if="mode === 'password'">
            <el-form-item><el-input v-model="username" placeholder="用户名（admin / duo_pc 多账号）" /></el-form-item>
            <el-form-item><el-input v-model="password" type="password" placeholder="口令（Admin@123）" show-password /></el-form-item>
          </template>
          <template v-else>
            <el-form-item><el-input v-model="phone" placeholder="手机号（13900009000 多账号）" /></el-form-item>
            <el-form-item>
              <div style="display:flex;gap:8px;width:100%">
                <el-input v-model="code" placeholder="短信验证码" />
                <el-button @click="sendCode">{{ smsSent ? '重新获取' : '获取验证码' }}</el-button>
              </div>
            </el-form-item>
          </template>
          <el-button type="primary" style="width:100%" :loading="loading" @click="submit">登录</el-button>
        </el-form>
        <p style="color:#909399;font-size:12px;margin-top:12px">
          dev：admin（平台SA）/ cuihu_pl（物业PL）单账号直登；<b>duo_pc 或手机 13900009000</b> 演示一号多账号选择。
        </p>
      </template>
    </el-card>
  </div>
</template>
