<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'

// 移动作业端外壳：顶栏(标题/角色) + 内容(router-view) + 底部 4 Tab。
// 角色 CO=催收员 / PC=协调员 由真实登录态(auth.me.role)决定,非演示切换。
const auth = useAuth()
const route = useRoute()
const router = useRouter()

onMounted(async () => { if (auth.isAuthed && !auth.me) await auth.fetchMe() })

const TABS = [
  { path: '/m', label: '工作台', icon: '🏠' },
  { path: '/m/cases', label: '案件', icon: '📋' },
  { path: '/m/calls', label: '通话记录', icon: '📞' },
  { path: '/m/me', label: '我的', icon: '👤' },
]
const activeTab = computed(() => {
  // 最长前缀匹配(/m/cases 优先于 /m)
  const m = [...TABS].sort((a, b) => b.path.length - a.path.length).find((t) => route.path === t.path || route.path.startsWith(t.path + '/'))
  return m?.path ?? '/m'
})
const title = computed(() => (route.meta.mtitle as string) || TABS.find((t) => t.path === activeTab.value)?.label || '有证慧催')
const roleLabel = computed(() => ({ CO: '催收员', PC: '协调员', VL: '负责人' } as any)[auth.me?.role ?? ''] || auth.me?.role || '')
</script>

<template>
  <div class="m-app">
    <header class="m-ab">
      <span>{{ title }}</span>
      <span class="m-rp" v-if="auth.me">{{ auth.me.name }} · {{ roleLabel }}</span>
    </header>

    <main class="m-body">
      <router-view />
    </main>

    <nav class="m-tabbar">
      <div
        v-for="t in TABS"
        :key="t.path"
        class="m-t"
        :class="{ on: activeTab === t.path }"
        @click="router.push(t.path)"
      >
        <span class="i">{{ t.icon }}</span>{{ t.label }}
      </div>
    </nav>
  </div>
</template>
