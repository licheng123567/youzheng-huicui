<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'
import DsDrawer from './DsDrawer.vue'
import { useAuth } from '../stores/auth'
import { APP_DOWNLOAD_URL, canUseApp } from '../constants/app'

// 催收员 App 下载与使用说明。所有角色都能打开 ——
// 但 App 只对催收员开放（BR-APP-01），管理角色打开它是为了**把链接发给手下的催收员**，
// 不是自己装。文案按角色分，避免让物业负责人装完发现进不去。
const model = defineModel<boolean>({ required: true })

const auth = useAuth()
const qr = ref('')
const isCollector = computed(() => canUseApp(auth.me?.role))

watch(model, async (open) => {
  if (open && !qr.value) {
    qr.value = await QRCode.toDataURL(APP_DOWNLOAD_URL, { margin: 1, width: 180 })
  }
}, { immediate: true })

function copyUrl() {
  navigator.clipboard.writeText(APP_DOWNLOAD_URL)
    .then(() => ElMessage.success('已复制下载链接，可发给催收员'))
    .catch(() => ElMessage.warning('复制失败，请手动复制：' + APP_DOWNLOAD_URL))
}
</script>

<template>
  <DsDrawer v-model="model" title="催收员 App（Android）" :width="520">
    <div class="app-dl">
      <!-- 角色决定这一屏是「给你装的」还是「给你转发的」 -->
      <div v-if="isCollector" class="lead">
        用手机扫码安装，然后用你现在这个账号登录。
      </div>
      <div v-else class="lead warn">
        App 只对<b>催收员</b>开放，你的账号登录后不会进入作业界面。
        这个链接是给你转发给手下催收员的。
      </div>

      <div class="qr-row">
        <img v-if="qr" :src="qr" alt="App 下载二维码" class="qr" />
        <div class="qr-side">
          <div class="url" :title="APP_DOWNLOAD_URL">{{ APP_DOWNLOAD_URL }}</div>
          <div class="acts">
            <a class="btn" :href="APP_DOWNLOAD_URL">直接下载</a>
            <button class="btn ghost" @click="copyUrl">复制链接</button>
          </div>
          <div class="hint">手机需与本系统在同一网络才能打开这个地址。</div>
        </div>
      </div>

      <h4>安装</h4>
      <ol>
        <li>扫码或用手机浏览器打开上面的链接，下载 APK。</li>
        <li>系统提示「未知来源」时选择允许 —— 本 App <b>不上应用商店</b>，只走企业内部分发。</li>
        <li>安装后用平台账号登录（口令登录 / 短信验证码 / 一号多账号选择均支持）。</li>
      </ol>

      <h4>首次登录必须走完引导四步</h4>
      <ol>
        <li><b>授予权限</b>：通话状态、通话记录、拨号、通知。每一项都会说明拒绝后会怎样。</li>
        <li><b>开启系统「通话自动录音」</b>：在系统「电话」应用的设置里。
          <b>App 不能代你打开，也读不到它的状态。</b></li>
        <li><b>定位录音目录</b>：需要授予「所有文件访问」权限，否则读不到系统录下的通话录音。</li>
        <li><b>打一通测试电话</b>：<b>这是唯一能证明整条链路通了的检验。</b>
          前三步全绿也不代表你的手机真的录到了音。</li>
      </ol>

      <h4>日常怎么用</h4>
      <ul>
        <li>在案件详情点<b>拨号</b> → 跳到系统拨号盘 → 正常通话。平台不替你外呼。</li>
        <li>挂断后 App 自动去录音目录找这通电话的录音，匹配到案件后传回平台做转写与质检。</li>
        <li>「录音」页可以看到上传队列：待上传 / 上传中 / 等待重试 / 已上传。失败可一键重试。</li>
        <li>录音归属不明时（比如连续拨了两个号码），App <b>不会瞎猜</b>，会让你二选一。</li>
        <li>没检测到录音时，可以在案件详情手动选一个音频文件上传。</li>
      </ul>

      <h4>需要知道的边界</h4>
      <ul class="caveat">
        <li><b>只有 Android。</b>iOS 从系统层面禁止任何第三方应用读取通话录音，做不了。</li>
        <li><b>录音是你手机的系统功能录的，不是 App 录的。</b>系统「通话自动录音」没开，就不会有录音。</li>
        <li>原生 Android（Pixel 等）不提供系统通话录音，用这类手机只能手动上传。</li>
        <li>未接通的电话不会上传 —— 不浪费转写额度。</li>
        <li>退出登录会清空还没传上去的录音队列（会先提示），但<b>不会动你手机里的原始录音文件</b>。</li>
      </ul>
    </div>
  </DsDrawer>
</template>

<style scoped>
.app-dl { font-size: 13px; line-height: 1.75; color: #33383e; }
.lead { padding: 10px 12px; border-radius: 6px; background: #eef3ff; margin-bottom: 14px; }
.lead.warn { background: #fff7e6; }
.qr-row { display: flex; gap: 16px; align-items: flex-start; margin-bottom: 6px; }
.qr { width: 180px; height: 180px; border: 1px solid #e6e8eb; border-radius: 6px; flex: none; }
.qr-side { flex: 1; min-width: 0; }
.url {
  font-family: ui-monospace, Menlo, monospace; font-size: 12px; color: #5a6068;
  word-break: break-all; background: #f6f7f9; padding: 6px 8px; border-radius: 4px;
}
.acts { display: flex; gap: 8px; margin: 10px 0 6px; }
.btn {
  display: inline-block; padding: 6px 14px; border-radius: 4px; border: 1px solid var(--brand, #1e5eff);
  background: var(--brand, #1e5eff); color: #fff; font-size: 13px; cursor: pointer; text-decoration: none;
}
.btn.ghost { background: #fff; color: var(--brand, #1e5eff); }
.hint { font-size: 12px; color: #8492a6; }
h4 { margin: 18px 0 6px; font-size: 13px; font-weight: 600; color: #1f2429; }
ol, ul { margin: 0; padding-left: 20px; }
li { margin: 3px 0; }
.caveat li { color: #7a5a1e; }
</style>
