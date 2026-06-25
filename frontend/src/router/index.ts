import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
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
