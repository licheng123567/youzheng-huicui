import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    // M7 业主自助 H5：公开页(免登录·不进 AppLayout)，业主扫码/短信链接进入
    { path: '/pay/:token', name: 'owner-bill', component: () => import('../views/OwnerBillView.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
        { path: 'projects', name: 'projects', component: () => import('../views/ProjectsView.vue') },
        { path: 'projects/:id', name: 'project-detail', component: () => import('../views/ProjectDetailView.vue') },
        { path: 'batches', name: 'batches', component: () => import('../views/BatchesView.vue') },
        { path: 'sea', name: 'sea', component: () => import('../views/SeaView.vue') },
        { path: 'cases', name: 'cases', component: () => import('../views/CasesView.vue') },
        { path: 'cases/:id', name: 'case-detail', component: () => import('../views/CaseDetailView.vue') },
        { path: 'cases/:id/call/:callId', name: 'call-record', component: () => import('../views/CallRecordView.vue') },
        { path: 'batches/:id', name: 'batch-detail', component: () => import('../views/BatchDetailView.vue') },
        { path: 'settlement', name: 'settlement', component: () => import('../views/SettlementView.vue') },
        { path: 'settlement-out', name: 'settlement-out', component: () => import('../views/SettlementOutView.vue') },
        { path: 'co-commission', name: 'co-commission', component: () => import('../views/CoCommissionView.vue') },
        { path: 'recharge', name: 'recharge', component: () => import('../views/RechargeView.vue') },
        { path: 'sms', name: 'sms', component: () => import('../views/SmsView.vue') },
        { path: 'script-lib', name: 'script-lib', component: () => import('../views/ScriptLibView.vue') },
        { path: 'org-mgmt', name: 'org-mgmt', component: () => import('../views/OrgMgmtView.vue') },
        { path: 'my-settle', name: 'my-settle', component: () => import('../views/MySettleView.vue') },
        { path: 'legal', name: 'legal', component: () => import('../views/LegalView.vue') },
        { path: 'my-stats', name: 'my-stats', component: () => import('../views/MyStatsView.vue') },
        { path: 'my-links', name: 'my-links', component: () => import('../views/MyLinksView.vue') },
        { path: 'risks', name: 'risks', component: () => import('../views/RisksView.vue') },
        { path: 'reports', name: 'reports', component: () => import('../views/ReportsView.vue') },
        { path: 'evidence', name: 'evidence', component: () => import('../views/EvidenceView.vue') },
        { path: 'billing', name: 'billing', component: () => import('../views/BillingView.vue') },
        { path: 'call-records', name: 'call-records', component: () => import('../views/CallRecordsView.vue') },
        { path: 'audit-log', name: 'audit-log', component: () => import('../views/AuditLogView.vue') },
        { path: 'settings', name: 'settings', component: () => import('../views/SettingsView.vue') },
        { path: 'members', name: 'members', component: () => import('../views/MembersView.vue') },
        { path: 'notifications', name: 'notifications', component: () => import('../views/NotificationsView.vue') },
        { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue') },
        { path: 'search', name: 'search', component: () => import('../views/SearchView.vue') },
      ],
    },
    // 移动作业端(催收员/协调员)：独立 MobileLayout(顶栏+底部 Tab)，受登录保护。
    {
      path: '/m',
      component: () => import('../layouts/MobileLayout.vue'),
      children: [
        { path: '', name: 'm-home', component: () => import('../views/m/MHomeView.vue') },
        { path: 'cases', name: 'm-cases', component: () => import('../views/m/MCasesView.vue') },
        { path: 'cases/:id', name: 'm-case-detail', component: () => import('../views/m/MCaseDetailView.vue'), meta: { mtitle: '案件详情' } },
        { path: 'calls', name: 'm-calls', component: () => import('../views/m/MCallsView.vue') },
        { path: 'me', name: 'm-me', component: () => import('../views/m/MMeView.vue') },
      ],
    },
  ],
})

// 路由守卫：未登录访问受保护页 → 跳登录（前端兜底，真隔离在服务端 x-data-scope）。
router.beforeEach((to) => {
  const auth = useAuth()
  if (!to.meta.public && !auth.isAuthed) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.isAuthed) return { path: '/' }
  return true
})

export default router
