<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'

// 消息中心(GET /notifications · BR-M4-23 互推闭环)：未读列表 + 标已读。
const items = ref<any[]>([])
// 分页：此前硬编码 page:1 且无分页控件 —— 超出一页的数据静默消失，用户无从得知。
const page = ref(1)
const size = ref(50)
const total = ref(0)
function onPage(p: number) { page.value = p; load() }

const unreadOnly = ref(false)
// 后端实际会发四类通知：TICKET_NEW/TICKET_RECEIPT(FollowUpM4Controller)、
// QC_HANDLING/QC_RECTIFIED(QcM5Controller)。少映射一个，用户就会看到 `QC_HANDLING` 这样的裸码。
const TYPE_LABEL: Record<string, string> = {
  TICKET_NEW: '待处理工单',
  TICKET_RECEIPT: '工单回执',
  QC_HANDLING: '质检处理决定',
  QC_RECTIFIED: '整改回执',
}
// 待办类(war 橙) vs 回执类(suc 绿)
const WARN_TYPES = new Set(['TICKET_NEW', 'QC_HANDLING'])

async function load() {
  const { data } = await api.GET('/notifications', { params: { query: { unreadOnly: unreadOnly.value, page: page.value, size: size.value } } as any })
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
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
          <span class="tag" :class="WARN_TYPES.has(n.type) ? 'war' : 'suc'">{{ TYPE_LABEL[n.type] || n.type }}</span>
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

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />
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
