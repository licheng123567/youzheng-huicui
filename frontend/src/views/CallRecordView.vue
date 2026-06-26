<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// US-M4-12 独立通话记录页：GET /recordings/{callId}(CallRecording) + ai-review。
const route = useRoute(); const router = useRouter()
const callId = String(route.params.callId)
const rec = ref<any>(null); const review = ref<any>(null)

onMounted(async () => {
  const { data, error } = await api.GET('/recordings/{id}', { params: { path: { id: callId } } })
  if (error || !data) { ElMessage.error('通话记录加载失败'); return }
  rec.value = data
  if ((data as any).status === 'READY') review.value = (await api.GET('/recordings/{id}/ai-review', { params: { path: { id: callId } } })).data
})
</script>

<template>
  <el-card v-if="rec">
    <template #header>
      <el-button link @click="router.back()">← 返回案件</el-button>
      <span style="margin-left:8px">通话记录 #{{ rec.id }} · 案件 {{ rec.caseId }}</span>
    </template>
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="来源">{{ rec.source }}</el-descriptions-item>
      <el-descriptions-item label="状态"><el-tag size="small" :type="rec.status==='READY'?'success':rec.status==='FAILED'?'danger':'warning'">{{ rec.status }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="时长">{{ rec.durationSec }}s</el-descriptions-item>
      <el-descriptions-item label="号码">{{ rec.phone ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="录制时间" :span="2">{{ rec.recordedAt ?? '—' }}</el-descriptions-item>
      <el-descriptions-item v-if="rec.failureCode" label="失败原因" :span="3">{{ rec.failureCode }} {{ rec.failureMessage }}</el-descriptions-item>
    </el-descriptions>
    <el-divider content-position="left">转写文本</el-divider>
    <div style="white-space:pre-wrap;background:#f5f7fa;padding:12px;border-radius:4px;min-height:60px">{{ rec.transcript ?? '（暂无转写）' }}</div>
    <template v-if="review">
      <el-divider content-position="left">AI 复盘</el-divider>
      <p><b>小结：</b>{{ review.summary }}</p>
      <!-- M-02: 说话人分离对话气泡(review.dialogue) -->
      <template v-if="review.dialogue?.length">
        <div style="max-height:320px;overflow:auto;background:#f5f7fa;padding:8px;border-radius:4px;margin:6px 0">
          <div v-for="(turn,ti) in review.dialogue" :key="ti" style="display:flex;margin:4px 0" :style="{ justifyContent: turn.speaker==='AGENT' || turn.speaker==='催收员' ? 'flex-end' : 'flex-start' }">
            <div :style="{ maxWidth:'72%', background: turn.speaker==='AGENT' || turn.speaker==='催收员' ? '#d9ecff' : '#fff', border:'1px solid #e4e7ed', borderRadius:'6px', padding:'6px 10px' }">
              <div style="font-size:12px;color:#909399">{{ turn.speaker }}</div>
              <div style="font-size:13px;white-space:pre-wrap">{{ turn.text }}</div>
            </div>
          </div>
        </div>
      </template>
      <!-- M-02: 风险标签追加 segmentTs 片段定位 -->
      <p v-if="review.risks?.length"><b>风险：</b><el-tag v-for="(r,ri) in review.risks" :key="ri" type="danger" size="small" style="margin:2px">{{ r.level }} {{ r.desc }}<span v-if="r.segmentTs"> @{{ r.segmentTs }}</span></el-tag></p>
      <el-card v-for="s in review.suggestions ?? []" :key="s.id" shadow="never" style="margin:6px 0"><b>{{ s.title }}</b> <el-tag size="small">{{ s.type }}</el-tag><div style="color:#606266;font-size:13px">{{ s.body }}</div></el-card>
    </template>
  </el-card>
</template>
