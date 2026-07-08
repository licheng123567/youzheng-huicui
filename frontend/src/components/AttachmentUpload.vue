<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'

// 附件上传复用件（跟进记录附件 / 送达存证凭证共用）：
//  - 直传：桌面选文件 → multipart 上传 → emit uploaded
//  - 扫码：建会话 → 弹二维码(手机扫码打开 /u/{token} 公开页上传) → 桌面轮询自动附上
// 后端 AttachmentController 为非 OpenAPI 契约端点，故用裸 fetch(带 Bearer)，照 uploadRecording 范式。
// deliveryType：非空表示上传件为「送达凭证」，随附件写入 case_attachment.delivery_type → 进协调员「送达管理」列表；
// 不传/空则为普通跟进附件（不进送达管理）。扫码会话也携带，手机上传件继承。
const props = withDefaults(defineProps<{ caseId: string; qr?: boolean; deliveryType?: string }>(), { qr: true })
const emit = defineEmits<{ uploaded: [items: Array<{ id: string; name: string; url: string }>] }>()

const token0 = () => localStorage.getItem('token') || ''

// ── 直传 ──
const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)
function pick() { fileInput.value?.click() }
async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData(); fd.append('file', file)
    if (props.deliveryType) fd.append('deliveryType', props.deliveryType)
    const r = await fetch(`/v1/cases/${props.caseId}/attachments`, {
      method: 'POST', headers: { Authorization: `Bearer ${token0()}` }, body: fd,
    })
    if (!r.ok) { ElMessage.error('上传失败 ' + r.status); return }
    const data = await r.json()
    emit('uploaded', [{ id: String(data.id), name: data.name, url: data.url }])
    ElMessage.success('已上传：' + data.name)
  } catch (err: any) {
    ElMessage.error('上传失败：' + (err?.message ?? ''))
  } finally {
    uploading.value = false
    input.value = ''
  }
}

// ── 扫码上传 ──
const qrDlg = ref(false)
const qrImg = ref('')
const qrUrl = ref('')
const token = ref('')
const seen = ref<Set<string>>(new Set())
let timer: any = null

async function openScan() {
  try {
    const q = props.deliveryType ? `?deliveryType=${encodeURIComponent(props.deliveryType)}` : ''
    const r = await fetch(`/v1/cases/${props.caseId}/upload-sessions${q}`, {
      method: 'POST', headers: { Authorization: `Bearer ${token0()}` },
    })
    if (!r.ok) { ElMessage.error('创建扫码会话失败 ' + r.status); return }
    const data = await r.json()
    token.value = data.token
    qrUrl.value = `${location.origin}/u/${token.value}`
    qrImg.value = await QRCode.toDataURL(qrUrl.value, { margin: 1, width: 180 })
    seen.value = new Set()
    qrDlg.value = true
    startPoll()
  } catch (err: any) {
    ElMessage.error('扫码会话异常：' + (err?.message ?? ''))
  }
}
function startPoll() { stopPoll(); timer = setInterval(poll, 3000) }
function stopPoll() { if (timer) { clearInterval(timer); timer = null } }
async function poll() {
  if (!token.value) return
  try {
    const r = await fetch(`/v1/upload-sessions/${token.value}`, { headers: { Authorization: `Bearer ${token0()}` } })
    if (!r.ok) return
    const data = await r.json()
    const fresh = (data.items || []).filter((it: any) => !seen.value.has(String(it.id)))
    if (fresh.length) {
      fresh.forEach((it: any) => seen.value.add(String(it.id)))
      emit('uploaded', fresh.map((it: any) => ({ id: String(it.id), name: it.name, url: it.url })))
      ElMessage.success(`扫码已上传 ${fresh.length} 个文件`)
    }
  } catch { /* 轮询容错：静默重试 */ }
}
function closeScan() { qrDlg.value = false; stopPoll() }
onUnmounted(stopPoll)
</script>

<template>
  <span style="display:inline-flex;gap:8px;flex-wrap:wrap;align-items:center">
    <input ref="fileInput" type="file" accept="image/*,*/*" hidden @change="onFile" />
    <button class="btn df sm" :disabled="uploading" @click="pick">📎 {{ uploading ? '上传中…' : '选择文件/图片' }}</button>
    <button v-if="qr" class="btn df sm" @click="openScan">📱 扫码上传</button>
  </span>

  <el-dialog v-model="qrDlg" title="扫码上传（手机拍照 / 相册 / 文件）" width="360px" append-to-body @close="closeScan">
    <div style="text-align:center">
      <img v-if="qrImg" :src="qrImg" width="180" height="180" alt="扫码上传二维码" style="border:1px solid var(--bd);border-radius:8px" />
      <div class="note" style="margin-top:10px;font-size:12px">用手机相机/微信扫码打开上传页，选好图片或文件即可；<br />上传后本窗口会自动附上（15 分钟内有效）。</div>
      <div class="note" style="margin-top:6px;font-size:11px;word-break:break-all;color:var(--sec)">{{ qrUrl }}</div>
    </div>
    <template #footer>
      <el-button type="primary" @click="closeScan">完成</el-button>
    </template>
  </el-dialog>
</template>
