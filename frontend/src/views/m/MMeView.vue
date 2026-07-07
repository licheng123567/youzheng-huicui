<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../../stores/auth'
import { api } from '../../api/client'

// 移动「我的」:主体信息 + 我的结算(CO 自查 /me/settlement) + 账号与安全(改密 /me/password) + 退出。
const auth = useAuth()
const router = useRouter()
const me = computed(() => auth.me)
const roleLabel = computed(() => ({ CO: '催收员', PC: '协调员', VL: '负责人' } as any)[me.value?.role ?? ''] || me.value?.role || '')
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

const settle = ref<any>(null)
const items = computed<any[]>(() => settle.value?.items ?? settle.value?.batches ?? [])

const tmsg = ref(''); let tt: any
function toast(m: string) { tmsg.value = m; clearTimeout(tt); tt = setTimeout(() => (tmsg.value = ''), 1800) }

// 改密
const pf = ref({ oldPassword: '', newPassword: '', confirm: '' })
const showPwd = ref(false)
async function savePwd() {
  if (pf.value.newPassword.length < 6) return toast('新密码至少 6 位')
  if (pf.value.newPassword !== pf.value.confirm) return toast('两次新密码不一致')
  const { error } = await api.POST('/me/password', { body: { oldPassword: pf.value.oldPassword, newPassword: pf.value.newPassword } as any })
  if (error) return toast('改密失败：' + ((error as any)?.message ?? ''))
  pf.value = { oldPassword: '', newPassword: '', confirm: '' }; showPwd.value = false; toast('密码已更新')
}

async function load() {
  if (me.value?.role === 'CO') {
    const { data } = await api.GET('/me/settlement', {})
    settle.value = data ?? null
  }
}
function logout() { auth.logout(); router.push('/login') }
onMounted(async () => { if (auth.isAuthed && !me.value) await auth.fetchMe(); load() })
</script>

<template>
  <div>
    <div class="mc" v-if="me">
      <b>{{ me.org?.name }} · {{ me.name }}（{{ roleLabel }}）</b>
      <div class="mini" style="margin-top:6px">账号 {{ (me as any).username || me.name }} · 数据范围 {{ (me as any).dataScope || '本范围' }}</div>
    </div>

    <!-- 我的结算(CO) -->
    <template v-if="me?.role === 'CO'">
      <div class="sec">我的结算 / 佣金（只读·服务商内部提成）</div>
      <div class="info3" style="margin-bottom:10px">
        <div><div class="v">{{ yuan(settle?.totalCents) }}</div><div class="k">累计提成</div></div>
        <div><div class="v" style="color:#15803d">{{ yuan(settle?.settledCents) }}</div><div class="k">已结</div></div>
        <div><div class="v" style="color:#dc2626">{{ yuan(settle?.unsettledCents) }}</div><div class="k">待结</div></div>
      </div>
      <div class="mc" v-for="(r, i) in items" :key="i">
        <div class="row">
          <b>{{ r.batchNo || r.batch || r.no || ('批次 ' + (r.batchId ?? '')) }}</b>
          <span class="badge" :class="(r.settled || r.status === '已结') ? 'b-gr' : 'b-or'">{{ (r.settled || r.status === '已结') ? '已结' : '待结' }}</span>
        </div>
        <div class="row" style="margin-top:6px">
          <span class="mini">回款 {{ yuan(r.baseCents ?? r.repayCents) }} · 比例 {{ r.rate ?? (r.commRate != null ? (r.commRate * 100).toFixed(0) : '—') }}%</span>
          <span class="due" style="color:#13245b">提成 {{ yuan(r.commCents ?? r.amountCents) }}</span>
        </div>
      </div>
      <div class="mini" v-if="!items.length" style="text-align:center">暂无结算明细</div>
    </template>

    <!-- 账号与安全 -->
    <div class="sec">账号与安全</div>
    <div class="mc" v-if="!showPwd" role="button" tabindex="0" style="cursor:pointer" @click="showPwd = true">
      <div class="row"><b>设置 / 修改密码</b><span class="mini">›</span></div>
    </div>
    <template v-else>
      <input class="inp" type="password" style="width:100%;margin-bottom:8px" v-model="pf.oldPassword" placeholder="原密码（首次设置可留空）" />
      <input class="inp" type="password" style="width:100%;margin-bottom:8px" v-model="pf.newPassword" placeholder="新密码（至少 6 位）" />
      <input class="inp" type="password" style="width:100%" v-model="pf.confirm" placeholder="确认新密码" />
      <button class="mbtn pri" style="margin-top:10px" @click="savePwd">保存密码</button>
      <button class="mbtn gho" style="margin-top:8px" @click="showPwd = false">取消</button>
    </template>

    <button class="mbtn gho" style="margin-top:14px" @click="logout">退出登录</button>

    <div class="m-toast" v-if="tmsg">{{ tmsg }}</div>
  </div>
</template>
