<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()

onMounted(() => { if (auth.isAuthed && !auth.me) auth.fetchMe() })

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
        <span>{{ auth.me.name }}（{{ auth.me.role }}）· {{ auth.me.org?.name }}</span>
        <el-button size="small" @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="180px" style="background:#f5f7fa">
        <el-menu :default-active="$route.path" router>
          <el-menu-item index="/dashboard">当前主体</el-menu-item>
          <el-menu-item index="/projects">项目</el-menu-item>
          <el-menu-item index="/batches">批次</el-menu-item>
          <el-menu-item index="/sea">公海</el-menu-item>
          <el-menu-item index="/cases">案件</el-menu-item>
          <el-menu-item index="/settlement">结算</el-menu-item>
          <el-menu-item index="/risks">质检</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
