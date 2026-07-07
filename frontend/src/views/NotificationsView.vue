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
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>消息中心</div>
      <div class="ops">
        <span class="note" style="margin:0">互推闭环 BR-M4-23 · 工单转出/回执通知</span>
        <el-switch v-model="unreadOnly" active-text="仅未读" @change="load" />
      </div>
    </div>

    <!-- 消息清单（ds-admin 时间线 .tl）：类型标签 + 标题/正文 + 时间 + 未读红点 -->
    <div class="tl" v-if="items.length">
      <div v-for="n in items" :key="n.id" class="msg-e" :class="{ read: n.read }">
        <div class="msg-line">
          <span class="tag" :class="n.type === 'TICKET_NEW' ? 'war' : 'suc'">{{ TYPE_LABEL[n.type] || n.type }}</span>
          <span class="msg-title">{{ n.title }}</span>
          <span class="msg-right">
            <span class="tm">{{ String(n.createdAt).slice(0, 16).replace('T', ' ') }}</span>
            <span v-if="!n.read" class="msg-dot" :style="{ background: 'var(--danger)' }"></span>
            <button v-if="!n.read" class="btn txt sm" @click="markRead(n)">标已读</button>
          </span>
        </div>
        <div v-if="n.body" class="msg-body">{{ n.body }}</div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-else class="wl-empty">暂无消息</div>
  </div>
</template>

<style scoped>
.card-h .ops { gap: 14px; }
.tl { padding-left: 20px; }
.msg-e { position: relative; margin-bottom: 16px; font-size: 14px; color: var(--reg); }
.msg-e::before { content: ""; position: absolute; left: -20px; top: 5px; width: 10px; height: 10px; border-radius: 50%; background: var(--primary); }
.msg-e::after { content: ""; position: absolute; left: -15.5px; top: 15px; width: 1px; height: calc(100% - 4px); background: var(--bd); }
.msg-e:last-child::after { display: none; }
.msg-e.read { opacity: .62; }
.msg-line { display: flex; align-items: center; gap: 8px; }
.msg-title { color: var(--txt); }
.msg-right { margin-left: auto; display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.msg-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.msg-body { color: var(--sec); font-size: 12px; margin-top: 4px; line-height: 1.6; }
</style>
