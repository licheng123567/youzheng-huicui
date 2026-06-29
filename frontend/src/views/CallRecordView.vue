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
  <div v-if="rec" class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>通话记录 #{{ rec.id }} · 案件 {{ rec.caseId }}</div>
      <div class="ops">
        <el-button class="btn df sm" text @click="router.back()">← 返回案件</el-button>
      </div>
    </div>

    <!-- 录音条 -->
    <div class="player">
      <span>{{ rec.status==='READY' ? '▶' : '·' }}</span>
      <div style="flex:1;height:4px;background:#e4e7ed;border-radius:2px;position:relative"><div style="width:0;height:100%;background:var(--primary);border-radius:2px"></div></div>
      <span>{{ rec.durationSec ?? 0 }}s</span>
      <span class="tag" :class="rec.status==='READY'?'suc':rec.status==='FAILED'?'dan':'war'">{{ rec.status }}</span>
    </div>

    <!-- 键值信息 -->
    <div class="sec-title">通话信息</div>
    <div class="desc">
      <div class="r"><div class="k">来源</div><div class="v">{{ rec.source ?? '—' }}</div></div>
      <div class="r"><div class="k">状态</div><div class="v"><span class="tag" :class="rec.status==='READY'?'suc':rec.status==='FAILED'?'dan':'war'">{{ rec.status }}</span></div></div>
      <div class="r"><div class="k">时长</div><div class="v num">{{ rec.durationSec ?? 0 }}s</div></div>
      <div class="r"><div class="k">号码</div><div class="v">{{ rec.phone ?? '—' }}</div></div>
      <div class="r"><div class="k">录制时间</div><div class="v">{{ rec.recordedAt ?? '—' }}</div></div>
      <div v-if="rec.failureCode" class="r"><div class="k">失败原因</div><div class="v">{{ rec.failureCode }} {{ rec.failureMessage }}</div></div>
    </div>

    <!-- 转写文本 -->
    <div class="sec-title">转写文本</div>
    <div style="white-space:pre-wrap;background:#f7f9fc;border:1px solid var(--bd);padding:12px;border-radius:6px;min-height:60px;font-size:13px;line-height:1.7">{{ rec.transcript ?? '（暂无转写）' }}</div>

    <template v-if="review">
      <div class="sec-title">AI 复盘</div>
      <div class="alert info" style="margin-top:0">📋 {{ review.summary }}</div>

      <!-- M-02: 说话人分离对话气泡(review.dialogue) -->
      <template v-if="review.dialogue?.length">
        <div class="sec-title">对话记录 · ASR 转写（说话人分离）</div>
        <div class="chat" style="max-height:320px;overflow:auto;background:#f7f9fc;border:1px solid var(--bd);padding:8px;border-radius:6px">
          <div v-for="(turn,ti) in review.dialogue" :key="ti" class="row" :class="(turn.speaker==='AGENT' || turn.speaker==='催收员') ? 'me' : 'them'">
            <div>
              <div class="bub" style="white-space:pre-wrap">{{ turn.text }}</div>
              <div class="meta">{{ turn.speaker }}</div>
            </div>
          </div>
        </div>
      </template>

      <!-- M-02: 风险条(level→l1/l2)，保留 segmentTs 片段定位 -->
      <template v-if="review.risks?.length">
        <div class="sec-title">质检风险点</div>
        <div v-for="(r,ri) in review.risks" :key="ri" class="riskbar" :class="r.level==='L2'||r.level==='HIGH'?'l2':'l1'">
          {{ r.level==='L2'||r.level==='HIGH' ? '🔴' : '⚠️' }} {{ r.level }}：{{ r.desc }}<span v-if="r.segmentTs"> @{{ r.segmentTs }}</span>
        </div>
      </template>

      <!-- 下一步建议 -->
      <template v-if="(review.suggestions ?? []).length">
        <div class="sec-title">下一步建议</div>
        <div class="aicard script" v-for="s in review.suggestions" :key="s.id">
          <div class="ti">{{ s.title }} <span class="tag inf" style="font-weight:400">{{ s.type }}</span></div>
          <div class="tx">{{ s.body }}</div>
        </div>
      </template>
    </template>
  </div>
</template>

