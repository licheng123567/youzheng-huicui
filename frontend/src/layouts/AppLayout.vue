<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

const auth = useAuth()
const route = useRoute()
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

// 菜单按原型「每个角色 nav」1:1（docs/ui/高保真 roles[role].nav）。原型 view-key → 实现端真实路由；
// 原型中被实现端合并/未建独立页的 key 映射为 null（不进菜单，差异见交付说明，待产品决策是否拆分建页）。
const KEY2PATH: Record<string, string | null> = {
  workbench: '/dashboard', dispatch: '/batches', platformSea: '/sea', providerSea: '/sea',
  projects: '/projects', cases: '/cases', myCases: '/cases', callLog: '/call-records',
  qc: '/risks', evidence: '/evidence',
  // 结算三线已拆独立页（收佣对账/付佣对账/内催佣金）
  reconIn: '/settlement', reconOut: '/settlement-out', coCommission: '/co-commission', mySettle: '/my-settle',
  // 计费三项已拆（用量/充值/短信）
  billing: '/billing', recharge: '/recharge', sms: '/sms',
  members: '/members', orgMgmt: '/org-mgmt', settings: '/settings', playbookLib: '/script-lib',
  reports: '/reports', audit: '/audit-log', inbox: '/notifications',
  // 后端缺专属列表端点，前端用现有端点拼真数据建成（见 docs/api/前端1to1-后端缺口清单.md P1）：
  legal: '/legal', myStats: '/my-stats', myLinks: '/my-links',
}
const PATH2LABEL: Record<string, string> = {
  '/dashboard': '工作台', '/batches': '批次', '/sea': '公海', '/projects': '项目',
  '/cases': '案件', '/call-records': '通话记录', '/risks': '质检', '/evidence': '存证',
  '/settlement': '收佣对账', '/settlement-out': '付佣对账', '/co-commission': '内催佣金',
  '/billing': '计费', '/recharge': '充值', '/sms': '短信', '/my-settle': '我的结算',
  '/members': '成员', '/org-mgmt': '组织管理', '/settings': '设置', '/script-lib': '话术库',
  '/reports': '报表', '/audit-log': '审计日志', '/notifications': '消息',
  '/legal': '法务', '/my-stats': '我的统计', '/my-links': '我的缴费链接',
}
type NavItem = string | { group: string }
const NAV_BY_ROLE: Record<string, NavItem[]> = {
  SA: [{ group: '业务' }, 'workbench', 'dispatch', 'platformSea', 'projects', 'cases', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'reconOut', 'billing', 'recharge', 'sms', { group: '系统' }, 'orgMgmt', 'members', 'settings', 'reports', 'audit'],
  SE: [{ group: '业务' }, 'workbench', 'dispatch', 'platformSea', 'projects', 'cases', { group: '能力' }, 'playbookLib', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'reconOut', 'billing', { group: '系统' }, 'orgMgmt', 'members', 'settings', 'audit', { group: '报表' }, 'reports'],
  PL: [{ group: '业务' }, 'workbench', 'projects', 'cases', 'qc', 'evidence', { group: '财务' }, 'reconIn', 'billing', 'recharge', 'sms', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox'],
  PC: [{ group: '业务' }, 'workbench', 'cases', 'callLog', 'myLinks', { group: '项目' }, 'projects', { group: '能力' }, 'qc', 'legal', 'evidence', { group: '财务' }, 'reconIn', { group: '我的' }, 'myStats', { group: '消息' }, 'inbox'],
  VL: [{ group: '业务' }, 'workbench', 'providerSea', 'projects', 'qc', 'cases', { group: '财务' }, 'reconOut', 'coCommission', 'recharge', 'billing', { group: '管理' }, 'reports', 'members', 'audit', { group: '消息' }, 'inbox'],
  CO: [{ group: '业务' }, 'workbench', 'myCases', 'providerSea', 'callLog', 'myLinks', { group: '我的' }, 'myStats', 'mySettle', { group: '消息' }, 'inbox'],
}
// 解析角色 nav → 分组菜单（按路由去重，保序）。未知角色兜底显示全部路由。
const groups = computed(() => {
  const role = auth.me?.role ?? ''
  const nav = NAV_BY_ROLE[role]
  if (!nav) return [{ group: '导航', items: Object.entries(PATH2LABEL).map(([path, label]) => ({ path, label })) }]
  const out: { group: string; items: { path: string; label: string }[] }[] = []
  const seen = new Set<string>()
  let cur: { group: string; items: { path: string; label: string }[] } | null = null
  for (const it of nav) {
    if (typeof it === 'object') { cur = { group: it.group, items: [] }; out.push(cur); continue }
    const path = KEY2PATH[it]
    if (!path || seen.has(path) || !cur) continue
    seen.add(path)
    cur.items.push({ path, label: PATH2LABEL[path] })
  }
  return out.filter((g) => g.items.length)
})
// 当前路由对应的菜单标题（面包屑用）
const crumb = computed(() => groups.value.flatMap((g) => g.items).find((m) => route.path.startsWith(m.path))?.label ?? '')

// 侧栏图标（24 线性图标，复用 ds-admin .mi svg 描边样式）
const ICONS: Record<string, string> = {
  '/dashboard': 'M3 3h7v7H3z M14 3h7v7h-7z M14 14h7v7h-7z M3 14h7v7H3z',
  '/projects': 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  '/batches': 'M12 2 2 7l10 5 10-5z M2 17l10 5 10-5 M2 12l10 5 10-5',
  '/sea': 'M2 13h6l2 3h4l2-3h6 M5 5h14l3 8v5a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-5z',
  '/cases': 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6',
  '/risks': 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M9 12l2 2 4-4',
  '/evidence': 'M9 12l2 2 4-4 M12 2 4 5v6c0 5 8 11 8 11s8-6 8-11V5z',
  '/call-records': 'M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3 19.5 19.5 0 0 1-6-6 19.8 19.8 0 0 1-3-8.6A2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.3 1.8.7 2.7a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.4-1.4a2 2 0 0 1 2.1-.4c.9.3 1.8.5 2.7.6a2 2 0 0 1 1.7 2z',
  '/settlement': 'M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  '/billing': 'M2 7h20v12H2z M2 11h20',
  '/reports': 'M3 3v18h18 M7 14l3-3 3 3 5-6',
  '/audit-log': 'M9 6h11 M9 12h11 M9 18h11 M4 6h.01 M4 12h.01 M4 18h.01',
  '/settings': 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 3.6 15H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 9 5.1V5a2 2 0 1 1 4 0v.1A1.6 1.6 0 0 0 15 6.4a1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.4 1.5H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z',
  '/members': 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M23 21v-2a4 4 0 0 0-3-3.9 M16 3.1a4 4 0 0 1 0 7.8',
  '/settlement-out': 'M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  '/co-commission': 'M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  '/my-settle': 'M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
  '/recharge': 'M2 7h20v12H2z M2 11h20 M16 15h3',
  '/sms': 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  '/org-mgmt': 'M3 21h18 M5 21V7l8-4v18 M19 21V11l-6-4 M9 9h.01 M9 13h.01 M9 17h.01',
  '/script-lib': 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z',
  '/legal': 'M12 3 4 7v6c0 5 8 8 8 8s8-3 8-8V7z M9 12l2 2 4-4',
  '/my-stats': 'M3 3v18h18 M7 14l3-3 3 3 5-6',
  '/my-links': 'M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1 M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1',
}
function iconPaths(path: string): string {
  const d = ICONS[path] ?? 'M4 6h16 M4 12h16 M4 18h16'
  return d.split(' M').map((seg, i) => `<path d="${i ? 'M' + seg : seg}"/>`).join('')
}

const avatarChar = computed(() => (auth.me?.name ?? '?').charAt(0))

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app">
    <!-- 侧栏 -->
    <aside class="side">
      <div class="logo"><div class="d">慧</div> 有证慧催</div>
      <nav class="menu">
        <template v-for="g in groups" :key="g.group">
          <div class="mgrp">{{ g.group }}</div>
          <a
            v-for="m in g.items"
            :key="m.path"
            class="mi"
            :class="{ on: route.path.startsWith(m.path) }"
            @click="router.push(m.path)"
          >
            <svg viewBox="0 0 24 24" v-html="iconPaths(m.path)"></svg>
            <span>{{ m.label }}</span>
          </a>
        </template>
      </nav>
    </aside>

    <!-- 主区 -->
    <div class="main">
      <header class="top">
        <div class="crumb"><b>控制台</b><span class="sep">/</span>{{ crumb }}</div>
        <div class="right" v-if="auth.me">
          <input
            class="inp"
            v-model="q"
            placeholder="搜案件/业主/房号/电话"
            style="min-width:220px"
            @keyup.enter="doSearch"
          />
          <span class="link" style="position:relative" @click="router.push('/notifications')">
            消息<span v-if="unread > 0" style="position:absolute;top:-8px;right:-14px;background:var(--danger);color:#fff;font-size:11px;border-radius:9px;padding:1px 7px;line-height:1.4">{{ unread > 99 ? '99+' : unread }}</span>
          </span>
          <span class="link" @click="router.push('/profile')">
            {{ auth.me.name }}（{{ auth.me.role }}）· {{ auth.me.org?.name }}
          </span>
          <div class="av">{{ avatarChar }}</div>
          <span class="link" @click="logout">退出</span>
        </div>
      </header>

      <main class="body">
        <router-view />
      </main>
    </div>
  </div>
</template>
