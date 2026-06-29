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
const showPwd = ref(false)   // 密码明文切换（展示用）
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
  <div class="login-page">
    <!-- 左品牌栏 -->
    <div class="brand">
      <div class="logo">有证<span class="hl">慧催</span></div>
      <div class="tag">AI 辅助 · 物业费催收案件 CRM 撮合平台</div>
      <ul class="features">
        <li><div class="fi">🎧</div><div><div class="ft">AI 话术辅助</div><div class="fd">通话录音转写 + 画像策略，边打边给建议与风控提示</div></div></li>
        <li><div class="fi">📁</div><div><div class="ft">案件 CRM 流转</div><div class="fd">项目→批次→案件 主轴，公海/私海/管道看板一站作业</div></div></li>
        <li><div class="fi">⚖️</div><div><div class="ft">存证与法律服务</div><div class="fd">送达/录音/材料三类存证，催收函·律师函·起诉状</div></div></li>
        <li><div class="fi">📱</div><div><div class="ft">三端协同</div><div class="fd">PC 管理 · App 拨号录音 · 业主 H5 缴费，全程留痕</div></div></li>
      </ul>
      <div class="foot">© 有证慧催 · 控制台</div>
    </div>

    <!-- 右登录区 -->
    <div class="login-area">
      <div class="login-card">
        <h2>欢迎回来</h2>
        <div class="sub">请登录您的账号</div>

        <!-- 多账号选择 -->
        <template v-if="accounts.length">
          <div class="acct-pick" style="display:block">
            <div class="h">该手机号关联多个账号，请选择登录身份（一号多账号 BR-M1-11）</div>
            <div v-for="a in accounts" :key="a.accountId" class="a" role="button" tabindex="0"
                 @click="!loading && pick(a)" @keyup.enter="!loading && pick(a)">
              <span>{{ a.name }} · {{ a.role }} · {{ a.orgName }}</span>
              <span class="link">登录</span>
            </div>
          </div>
          <div class="tip"><span class="link" @click="accounts = []">‹ 返回重新登录</span></div>
        </template>

        <!-- 登录表单 -->
        <template v-else>
          <div class="mode-tabs">
            <div class="m" :class="{ on: mode === 'password' }" @click="mode = 'password'">账号密码</div>
            <div class="m" :class="{ on: mode === 'sms' }" @click="mode = 'sms'">手机验证码</div>
          </div>

          <form @submit.prevent="submit">
            <template v-if="mode === 'password'">
              <div class="field">
                <label class="l">账号名</label>
                <div class="wrap"><input v-model="username" autocomplete="username" placeholder="用户名（admin / duo_pc 多账号）" /></div>
              </div>
              <div class="field">
                <label class="l">密码</label>
                <div class="wrap">
                  <input v-model="password" :type="showPwd ? 'text' : 'password'" autocomplete="current-password" placeholder="口令（Admin@123）" @keyup.enter="submit" />
                  <span class="eye" @click="showPwd = !showPwd">{{ showPwd ? '隐藏' : '显示' }}</span>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="field">
                <label class="l">手机号</label>
                <div class="wrap"><input v-model="phone" type="tel" maxlength="11" placeholder="手机号（13900009000 多账号）" /></div>
              </div>
              <div class="field">
                <label class="l">验证码</label>
                <div class="code-row">
                  <input v-model="code" inputmode="numeric" maxlength="6" placeholder="6 位验证码" @keyup.enter="submit" />
                  <button class="btn df getcode" type="button" @click="sendCode">{{ smsSent ? '重新获取' : '获取验证码' }}</button>
                </div>
              </div>
            </template>
            <button class="btn-login" type="submit" :disabled="loading">{{ loading ? '登录中…' : '登 录' }}</button>
          </form>

          <div class="tip">
            dev：admin（平台SA）/ cuihu_pl（物业PL）单账号直登；<b>duo_pc 或手机 13900009000</b> 演示一号多账号选择。
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 登录页专属样式（源自 docs/ui/高保真/login.html，复用 ds-admin :root tokens） */
.login-page { display: flex; height: 100vh; }
.brand { width: 460px; flex: none; background: linear-gradient(155deg, #13245b 0%, #1d4ed8 55%, #2563EB 100%); color: #fff; padding: 48px 44px; position: relative; overflow: hidden; display: flex; flex-direction: column; justify-content: center; }
.brand::before { content: ""; position: absolute; width: 360px; height: 360px; border-radius: 50%; background: rgba(255, 255, 255, .05); top: -120px; right: -120px; }
.brand::after { content: ""; position: absolute; width: 240px; height: 240px; border-radius: 50%; background: rgba(255, 255, 255, .04); bottom: -80px; left: -60px; }
.brand .logo { font-size: 30px; font-weight: 800; letter-spacing: 1px; position: relative; }
.brand .logo .hl { background: linear-gradient(90deg, #bfdbff, #fff); -webkit-background-clip: text; background-clip: text; color: transparent; }
.brand .tag { margin-top: 10px; font-size: 14px; color: rgba(255, 255, 255, .78); position: relative; }
.features { list-style: none; padding: 0; margin: 48px 0 0; position: relative; }
.features li { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 24px; }
.features .fi { width: 42px; height: 42px; border-radius: 10px; background: rgba(255, 255, 255, .12); display: flex; align-items: center; justify-content: center; font-size: 20px; flex: none; }
.features .ft { font-size: 15px; font-weight: 600; }
.features .fd { font-size: 12.5px; color: rgba(255, 255, 255, .6); margin-top: 3px; line-height: 1.6; }
.brand .foot { position: absolute; bottom: 24px; left: 44px; font-size: 12px; color: rgba(255, 255, 255, .45); }
.login-area { flex: 1; display: flex; align-items: center; justify-content: center; background: #f5f7fb; }
.login-card { width: 380px; }
.login-card h2 { margin: 0 0 4px; font-size: 24px; color: var(--txt); }
.login-card .sub { color: var(--sec); font-size: 13px; margin-bottom: 22px; }
.mode-tabs { display: flex; gap: 18px; border-bottom: 1px solid var(--bd); margin-bottom: 18px; }
.mode-tabs .m { padding: 8px 0; font-size: 14px; color: var(--reg); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.mode-tabs .m.on { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }
.field { margin-bottom: 16px; }
.field .l { font-size: 13px; color: var(--reg); margin-bottom: 6px; display: block; }
.field .wrap { position: relative; }
.field input { width: 100%; border: 1px solid var(--bd2); border-radius: 6px; padding: 11px 12px; font-size: 14px; color: var(--txt); }
.field input:focus { border-color: var(--primary); outline: none; box-shadow: 0 0 0 3px rgba(37, 99, 235, .1); }
.field .eye { position: absolute; right: 12px; top: 11px; color: var(--sec); cursor: pointer; font-size: 13px; user-select: none; }
.code-row { display: flex; gap: 10px; } .code-row input { flex: 1; border: 1px solid var(--bd2); border-radius: 6px; padding: 11px 12px; font-size: 14px; color: var(--txt); } .code-row .getcode { white-space: nowrap; }
.btn-login { width: 100%; padding: 12px; font-size: 15px; border: none; border-radius: 6px; background: var(--primary); color: #fff; cursor: pointer; font-weight: 600; margin-top: 4px; }
.btn-login:hover:not(:disabled) { background: var(--primary-d); }
.btn-login:disabled { background: #a9c2f5; cursor: not-allowed; }
.acct-pick { border: 1px solid var(--bd); border-radius: 8px; overflow: hidden; }
.acct-pick .h { font-size: 12px; color: var(--sec); padding: 8px 12px; background: #fafafa; border-bottom: 1px solid var(--bd); }
.acct-pick .a { padding: 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid #f3f4f6; display: flex; justify-content: space-between; align-items: center; }
.acct-pick .a:hover { background: var(--primary-l); } .acct-pick .a:last-child { border: none; }
.tip { font-size: 12px; color: var(--sec); margin-top: 12px; line-height: 1.6; }
.link { color: var(--primary); cursor: pointer; }
@media (max-width: 820px) { .brand { display: none; } }
</style>
