<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

const auth = useAuth()
const router = useRouter()
const unread = ref(0)   // 消息中心未读红点(BR-M4-23)
const q = ref('')       // 全局搜索
function doSearch() { if (q.value.trim()) router.push({ path: '/search', query: { q: q.value.trim() } }) }

async function loadUnread() {
  if (!auth.isAuthed) return
  const { data } = await api.GET('/notifications/unread-count', {})
  unread.value = (data as any)?.count ?? 0
}
onMounted(async () => { if (auth.isAuthed && !auth.me) await auth.fetchMe(); loadUnread() })

// 导航按功能权限过滤(代表权限·any 命中即显；无 perms=所有角色可见)。服务端 x-permission/scope 才是真隔离，此为 UX 门控。
const isPlatform = computed(() => ['SA', 'SE'].includes(auth.me?.role ?? ''))
const hasAny = (perms?: string[]) => !perms || perms.some((p) => auth.has(p))
const MENU = [
  { path: '/dashboard', label: '当前主体' },
  { path: '/projects', label: '项目' },                                         // H-01: GET /projects x-data-scope:range 无 x-permission → 所有登录态可见列表；新建按钮在 ProjectsView 内按 proj.edit 门控
  { path: '/batches', label: '批次' },                                           // H-01: GET /batches x-data-scope:range 无 x-permission → 所有登录态可见列表；导入/派单按钮在 BatchesView 内按 batch.import/case.dispatch 门控
  { path: '/sea', label: '公海', perms: ['case.claim', 'case.dispatch', 'case.assign', 'case.accept'] },
  { path: '/cases', label: '案件' },
  { path: '/settlement', label: '结算', perms: ['payreq.create', 'payreq.complete', 'cocomm.self.view', 'cocomm.manage'] },
  { path: '/risks', label: '质检', perms: ['qc.dispose', 'qc.review', 'qc.escalate'] },
  { path: '/reports', label: '报表' },                                            // H-02: GET /reports/operation x-data-scope:range 无 x-permission → 全登录态可见(物业/服务商/平台各口径服务端裁剪)；导出按钮在 ReportsView 内按 report.export 门控
  { path: '/evidence', label: '存证', perms: ['evidence.create'], platform: true },
  { path: '/billing', label: '计费' },   // usage/recharge-log 无 x-permission·range 读(物业/服务商/平台均可看本范围)；充值按钮内部按 billing.recharge 门控
  { path: '/settings', label: '设置', platform: true },                          // H-03: GET /settings x-data-scope:platform 无 x-permission → SA/SE 可进只读；编辑按钮在 SettingsView 内按 settings.manage 禁用
  { path: '/members', label: '成员', perms: ['member.manage'] },
]
const menu = computed(() => MENU.filter((m: any) => (!m.perms && !m.platform) || hasAny(m.perms) || (m.platform && isPlatform.value)))

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container style="height: 100vh">
    <el-header style="display:flex;align-items:center;justify-content:space-between;background:#1f2d3d;color:#fff">
      <strong>有证慧催 · 控制台</strong>
      <div v-if="auth.me" style="display:flex;align-items:center;gap:12px">
        <el-input v-model="q" size="small" placeholder="搜案件/业主/房号/电话" style="width:200px" clearable
          @keyup.enter="doSearch"><template #append><el-button @click="doSearch">搜</el-button></template></el-input>
        <el-badge :value="unread" :hidden="unread === 0" :max="99">
          <el-button size="small" @click="router.push('/notifications')">消息</el-button>
        </el-badge>
        <el-button text style="color:#fff" @click="router.push('/profile')">{{ auth.me.name }}（{{ auth.me.role }}）· {{ auth.me.org?.name }}</el-button>
        <el-button size="small" @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="180px" style="background:#f5f7fa">
        <el-menu :default-active="$route.path" router>
          <el-menu-item v-for="m in menu" :key="m.path" :index="m.path">{{ m.label }}</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
