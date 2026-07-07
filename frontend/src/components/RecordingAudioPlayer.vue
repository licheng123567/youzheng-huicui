<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { downloadAuthedFile } from '../utils/download'

// 通话录音音频回放（BR-M4-01b · 全角色）：二进制流端点 GET /recordings/{id}/audio 需登录鉴权，
// 而原生 <audio src> 不能带 Authorization 头，故必须 fetch blob → objectURL 再喂给 <audio>。
// 无音频字节（种子/历史录音）→ 404 优雅提示；解析未完成的录音也可能暂无音频。
// 下载录音同源（fetch 带 Bearer → 落盘），凡能回听处（三栏/通话记录/移动详情）均可下载。
const props = withDefaults(defineProps<{
  recordingId: string
  autoLoad?: boolean   // true=挂载即拉取；默认 false，点按钮再拉（节流量）
  fileName?: string    // 下载落盘名（如 业主_房号）；缺省 录音_{id}.mp3
}>(), { autoLoad: false })

function downloadRec() {
  const name = (props.fileName ? props.fileName : '录音_' + props.recordingId) + '.mp3'
  downloadAuthedFile(`/v1/recordings/${props.recordingId}/audio`, name, '该录音暂无音频文件，无法下载。')
}

const audioUrl = ref('')
const loading = ref(false)
const msg = ref('')

function reset() {
  if (audioUrl.value) { URL.revokeObjectURL(audioUrl.value); audioUrl.value = '' }
  msg.value = ''
}

async function load() {
  if (!props.recordingId) return
  loading.value = true; msg.value = ''
  try {
    const res = await fetch(`/v1/recordings/${props.recordingId}/audio`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
    })
    if (!res.ok) {
      msg.value = res.status === 404
        ? '该录音暂无音频文件（App 自动上传或「手动上传」音频后可回听）。'
        : '音频加载失败（' + res.status + '）。'
      return
    }
    if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
    audioUrl.value = URL.createObjectURL(await res.blob())
  } catch {
    msg.value = '音频加载失败。'
  } finally {
    loading.value = false
  }
}

// 录音切换：清掉旧音频；autoLoad 时自动拉取新录音
watch(() => props.recordingId, () => { reset(); if (props.autoLoad) load() }, { immediate: props.autoLoad })
onBeforeUnmount(reset)
</script>

<template>
  <div class="rec-audio">
    <button v-if="!audioUrl && !loading" class="btn sm df" style="width:100%" @click="load">🎧 加载并回听录音</button>
    <div v-else-if="loading" class="note" style="font-size:12px;text-align:center;padding:4px 0">正在加载音频…</div>
    <audio v-if="audioUrl" :src="audioUrl" controls style="width:100%;margin-top:2px"></audio>
    <button v-if="audioUrl" class="btn sm txt" style="margin-top:2px" @click="downloadRec">⬇ 下载录音</button>
    <div v-if="msg" class="note" style="font-size:11px;margin-top:4px;color:var(--sec)">{{ msg }}</div>
  </div>
</template>
