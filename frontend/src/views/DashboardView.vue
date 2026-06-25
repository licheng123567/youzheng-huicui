<script setup lang="ts">
import { computed } from 'vue'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const me = computed(() => auth.me)
</script>

<template>
  <el-card v-if="me" header="当前主体（契约 GET /me）">
    <el-descriptions :column="2" border>
      <el-descriptions-item label="账号 ID">{{ me.accountId }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ me.name }}</el-descriptions-item>
      <el-descriptions-item label="角色">{{ me.role }}</el-descriptions-item>
      <el-descriptions-item label="组织">{{ me.org?.name }}（{{ me.org?.type }}）</el-descriptions-item>
      <el-descriptions-item label="数据范围" :span="2">
        {{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量（dataScope=null）' }}
      </el-descriptions-item>
      <el-descriptions-item label="权限点" :span="2">
        <el-tag v-for="p in me.permissions" :key="p" style="margin:2px">{{ p }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
  <el-empty v-else description="加载主体中…" />
</template>
