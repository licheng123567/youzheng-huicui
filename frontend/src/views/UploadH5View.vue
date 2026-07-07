<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'

// 公开免登录 H5 扫码上传页（对标 /pay/:token 业主账单页范式）：
// 桌面端 AttachmentUpload 生成二维码 → 手机扫码进入本页 → 选图/选文件上传
// → POST /v1/upload-sessions/{token}/file（公开·JwtAuthFilter 白名单）→ 桌面轮询自动附上。
const route = useRoute()
const token = String(route.params.token || '')

const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)
const expired = ref(false)
const done = ref<Array<{ name: string }>>([])
const errMsg = ref('')

function pick() { fileInput.value?.click() }
async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  errMsg.value = ''
  try {
    const fd = new FormData(); fd.append('file', file)
    const r = await fetch(`/v1/upload-sessions/${token}/file`, { method: 'POST', body: fd })
    if (r.status === 404) { expired.value = true; return }
    if (r.status === 422) { errMsg.value = '文件无效或超过 20MB 上限'; return }
    if (!r.ok) { errMsg.value = '上传失败（' + r.status + '）'; return }
    done.value.unshift({ name: file.name })
  } catch (err: any) {
    errMsg.value = '网络异常：' + (err?.message ?? '')
  } finally {
    uploading.value = false
    input.value = ''
  }
}
</script>

<template>
  <div class="wrap">
    <div class="card">
      <div class="title">上传送达凭证 / 附件</div>

      <template v-if="expired">
        <div class="alert">上传会话已过期，请让桌面端重新生成二维码后再扫。</div>
      </template>

      <template v-else>
        <p class="hint">选择图片（拍照或相册）或文件上传，可多次上传。上传后桌面端会自动收到。</p>
        <input ref="fileInput" type="file" accept="image/*,*/*" hidden @change="onFile" />
        <button class="up" :disabled="uploading" @click="pick">
          {{ uploading ? '上传中…' : '＋ 选择图片 / 文件' }}
        </button>
        <div v-if="errMsg" class="alert err">{{ errMsg }}</div>

        <div v-if="done.length" class="list">
          <div class="lt">已上传（{{ done.length }}）</div>
          <div v-for="(f, i) in done" :key="i" class="row">✅ {{ f.name }}</div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.wrap { min-height: 100vh; background: #f2f4f8; display: flex; justify-content: center; padding: 24px 16px; }
.card { width: 100%; max-width: 420px; background: #fff; border-radius: 14px; box-shadow: 0 2px 14px rgba(0,0,0,.06); padding: 22px 18px; }
.title { font-size: 18px; font-weight: 700; text-align: center; margin-bottom: 8px; }
.hint { font-size: 13px; color: #667; line-height: 1.7; margin: 0 0 16px; }
.up { width: 100%; padding: 14px; font-size: 16px; font-weight: 600; color: #fff; background: #2f6fed; border: 0; border-radius: 10px; cursor: pointer; }
.up:disabled { opacity: .6; }
.alert { margin-top: 14px; padding: 10px 12px; border-radius: 8px; background: #fff7e6; color: #ad6800; font-size: 13px; }
.alert.err { background: #fff1f0; color: #cf1322; }
.list { margin-top: 18px; }
.lt { font-size: 12px; color: #889; margin-bottom: 6px; }
.row { padding: 8px 10px; background: #f6f8fb; border-radius: 8px; font-size: 13px; margin-bottom: 6px; word-break: break-all; }
</style>
