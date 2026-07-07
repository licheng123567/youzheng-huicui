<script setup lang="ts">
import { ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'

// 缴费链接 H5 页展示卡：业主凭 /pay/{token} 无登录访问账单页（OwnerH5M7Controller）。
// preview=true：上方嵌入该页面的实时预览（同源 iframe，业主打开短信/微信链接看到的就是这个）。
// compact=true：窄栏紧凑排版（案件右栏"本会话"列表用）——小二维码、隐藏 URL 全文、操作竖排；
//   默认插槽渲染在操作区末尾，供调用方追加操作（如 作废）。
const props = withDefaults(defineProps<{ token: string; preview?: boolean; compact?: boolean }>(), { preview: false, compact: false })
const emit = defineEmits<{ 'send-sms': [] }>()

const url = ref('')
const qr = ref('')

async function render() {
  url.value = `${location.origin}/pay/${props.token}`
  qr.value = await QRCode.toDataURL(url.value, { margin: 1, width: 160 })
}
watch(() => props.token, render, { immediate: true })

function copyUrl() {
  navigator.clipboard.writeText(url.value).then(() => {
    ElMessage.success('已复制链接，可转发微信')
  }).catch(() => {
    ElMessage.warning('复制失败，请手动复制：' + url.value)
  })
}

function downloadQr() {
  const a = document.createElement('a')
  a.href = qr.value
  a.download = `缴费链接二维码-${props.token}.png`
  a.click()
}
</script>

<template>
  <!-- 紧凑模式：二维码 + 竖排操作，适配 ~300px 窄栏 -->
  <div v-if="compact" style="display:flex;gap:10px;align-items:center">
    <img v-if="qr" :src="qr" width="64" height="64" alt="缴费链接二维码" style="border-radius:6px;border:1px solid var(--bd);flex:none">
    <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px">
      <div style="display:flex;align-items:center;gap:6px"><a class="btn txt" style="padding:0;font-size:12px" @click="copyUrl">复制链接</a><span style="font-size:11px;color:var(--sec)">不扣条数</span></div>
      <div style="display:flex;align-items:center;gap:6px"><a class="btn txt" style="padding:0;font-size:12px" @click="downloadQr">下载二维码</a><span style="font-size:11px;color:var(--sec)">不扣条数</span></div>
      <div style="display:flex;align-items:center;gap:6px"><a class="btn txt" style="padding:0;font-size:12px" @click="emit('send-sms')">短信发送</a><span style="font-size:11px;color:var(--warning,#E6A23C)">扣1条短信</span></div>
      <slot />
    </div>
  </div>

  <div v-else>
    <!-- 业主 H5 账单页实时预览（同源 iframe，即业主打开链接看到的原样内容） -->
    <div v-if="preview" style="display:flex;justify-content:center;margin-bottom:12px">
      <div style="width:340px;max-width:100%;height:480px;border:1px solid var(--bd);border-radius:12px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,.08)">
        <iframe v-if="url" :src="url" title="业主账单页预览" style="width:100%;height:100%;border:0"></iframe>
      </div>
    </div>
    <div style="display:flex;gap:10px;align-items:flex-start">
      <img v-if="qr" :src="qr" width="80" height="80" alt="缴费链接二维码" style="border-radius:6px;border:1px solid var(--bd)">
      <div style="flex:1;min-width:0">
        <div class="note" style="margin:0;font-size:12px;word-break:break-all">{{ url }}</div>
        <div style="margin-top:6px;display:flex;gap:4px;flex-wrap:wrap;align-items:center">
          <a class="btn txt" @click="copyUrl">复制链接</a>
          <span class="tag suc" style="font-size:11px">不扣条数</span>
          <a class="btn txt" @click="downloadQr">下载二维码</a>
          <span class="tag suc" style="font-size:11px">不扣条数</span>
          <a class="btn txt" @click="emit('send-sms')">短信发送</a>
          <span class="tag war" style="font-size:11px">扣1条短信</span>
          <slot />
        </div>
      </div>
    </div>
  </div>
</template>
