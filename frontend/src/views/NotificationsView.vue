<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// 消息中心(GET /notifications · BR-M4-23 互推闭环)：未读列表 + 标已读。
const items = ref<any[]>([])
const unreadOnly = ref(false)
const TYPE_LABEL: Record<string, string> = { TICKET_NEW: '待处理工单', TICKET_RECEIPT: '工单回执' }

async function load() {
  const { data } = await api.GET('/notifications', { params: { query: { unreadOnly: unreadOnly.value, page: 1, size: 50 } } as any })
  items.value = (data as any)?.items ?? []
}
async function markRead(n: any) {
  if (n.read) return
  const { error } = await api.POST('/notifications/{id}/read', { params: { path: { id: String(n.id) } } } as any)
  if (error) { ElMessage.error('标记失败'); return }
  n.read = true
}
onMounted(load)
</script>

<template>
  <el-card header="消息中心（互推闭环 BR-M4-23 · 工单转出/回执通知）">
    <div style="margin-bottom:10px">
      <el-switch v-model="unreadOnly" active-text="仅未读" @change="load" />
    </div>
    <el-empty v-if="!items.length" description="暂无消息" :image-size="60" />
    <div v-for="n in items" :key="n.id" style="display:flex;align-items:center;gap:10px;padding:10px 4px;border-bottom:1px solid #f0f2f5"
      :style="{ background: n.read ? '' : '#f0f9ff' }">
      <el-badge is-dot :hidden="n.read" />
      <el-tag size="small" :type="n.type === 'TICKET_NEW' ? 'warning' : 'success'">{{ TYPE_LABEL[n.type] || n.type }}</el-tag>
      <div style="flex:1">
        <div>{{ n.title }}</div>
        <div v-if="n.body" style="color:#909399;font-size:12px">{{ n.body }}</div>
      </div>
      <span style="color:#c0c4cc;font-size:12px">{{ String(n.createdAt).slice(0, 16).replace('T', ' ') }}</span>
      <el-button v-if="!n.read" size="small" text type="primary" @click="markRead(n)">标已读</el-button>
    </div>
  </el-card>
</template>
