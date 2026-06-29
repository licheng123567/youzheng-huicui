<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

// M7 业主自助 H5：公开页(免登录)。业主扫码/短信链接进入 → GET /pay/{token} 查账单。
// 用原生 fetch(不走 authed client，public 端点无需 Bearer)。
const route = useRoute()
const token = String(route.params.token)
const bill = ref<any>(null)
const err = ref('')
const yuan = (c?: number) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

onMounted(async () => {
  try {
    const r = await fetch(`/v1/pay/${encodeURIComponent(token)}`)
    if (r.status === 404) { err.value = '账单链接无效或已过期'; return }
    if (!r.ok) { err.value = '加载失败（' + r.status + '）'; return }
    bill.value = await r.json()
  } catch { err.value = '网络错误' }
})
</script>

<template>
  <div class="ob-page">
    <div class="ob-wrap">
      <!-- 失效 / 错误态 -->
      <div v-if="err" class="ob-expired">
        <div class="ob-expired-ic">!</div>
        <div class="ob-expired-t">{{ err }}</div>
        <div class="ob-expired-d">请联系物业管理处重新发送缴费链接。</div>
        <div class="ob-foot">有证慧催 · 安全缴费平台</div>
      </div>

      <template v-else-if="bill">
        <!-- 头部：品牌渐变 + 大号金额 -->
        <div class="ob-hd">
          <div class="ob-hd-nm">{{ bill.community }}</div>
          <div class="ob-hd-amt"><small>应缴合计 </small>{{ yuan(bill.payableCents) }}</div>
          <span v-if="bill.reductionCents" class="ob-reduce-tag">已减免 {{ yuan(bill.reductionCents) }}</span>
        </div>

        <!-- 账单明细 -->
        <div class="ob-sec">
          <h4 class="ob-sec-h">账单明细</h4>
          <div v-if="bill.reductionCents" class="ob-li">
            <span>减免后应收</span>
            <span class="r">{{ yuan(bill.payableCents) }}</span>
          </div>
          <div v-if="bill.arrearagePeriods?.length" class="ob-li">
            <span>欠费账期</span>
            <span class="muted">{{ bill.arrearagePeriods.join('、') }}</span>
          </div>
          <div v-if="bill.feeStd" class="ob-li">
            <span>收费标准</span>
            <span class="muted">{{ bill.feeStd }}</span>
          </div>
        </div>

        <!-- 分期明细 -->
        <div v-if="bill.installments?.length" class="ob-sec">
          <h4 class="ob-sec-h">分期明细</h4>
          <div v-for="(it, i) in bill.installments" :key="i" class="ob-li">
            <span>{{ it.period }} · 到期 {{ it.dueDate }}</span>
            <span>
              <span class="num">{{ yuan(it.amountCents) }}</span>
              <span class="ob-tag" :class="it.status==='PAID' ? 'suc' : 'war'">{{ it.status }}</span>
            </span>
          </div>
        </div>

        <!-- 缴费方式 -->
        <div class="ob-pay">
          <h4 class="ob-sec-h ob-pay-h">缴费方式</h4>
          <div v-if="bill.payChannels?.wechatQr" class="ob-qr-wrap">
            <el-image :src="bill.payChannels.wechatQr" style="width:160px;height:160px" fit="contain">
              <template #error><div class="ob-qr-ph">微信收款码</div></template>
            </el-image>
            <div class="ob-pay-cap">微信扫码缴费</div>
          </div>
          <div v-if="bill.payChannels?.bankAccount" class="ob-bank">对公账户：{{ bill.payChannels.bankAccount }}</div>
          <button v-if="bill.onlinePay" class="ob-btn" type="button">在线支付</button>
        </div>

        <div class="ob-foot">如有疑问请联系物业 · 本页面由有证慧催提供</div>
      </template>

      <!-- 加载态 -->
      <div v-else class="ob-sec">
        <el-skeleton :rows="5" animated />
      </div>
    </div>
  </div>
</template>

<style scoped>
.ob-page{min-height:100vh;background:#f0f2f5;padding:16px 0;}
.ob-wrap{max-width:420px;margin:0 auto;}

/* 头部品牌渐变 */
.ob-hd{background:linear-gradient(135deg,#2563EB,#1d4ed8);color:#fff;padding:22px 18px;border-radius:8px;margin:0 12px;box-shadow:0 4px 16px rgba(37,99,235,.18);}
.ob-hd-nm{font-size:13px;opacity:.9;line-height:1.7;}
.ob-hd-amt{font-size:34px;font-weight:800;margin-top:8px;line-height:1.2;font-variant-numeric:tabular-nums;}
.ob-hd-amt small{font-size:16px;font-weight:400;opacity:.9;}
.ob-reduce-tag{display:inline-block;margin-top:10px;font-size:12px;padding:2px 9px;border-radius:4px;background:rgba(255,255,255,.18);color:#fff;}

/* 卡片小节 */
.ob-sec{background:#fff;margin:12px;border-radius:8px;padding:14px;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-sec-h{margin:0 0 10px;font-size:14px;font-weight:600;color:#303133;}

/* 明细行 */
.ob-li{display:flex;justify-content:space-between;align-items:center;padding:7px 0;border-bottom:1px solid #ebeef5;font-size:14px;line-height:1.6;color:#606266;}
.ob-li:last-child{border:none;}
.ob-li .r{color:#F56C6C;font-weight:600;white-space:nowrap;margin-left:8px;}
.ob-li .muted{color:#909399;white-space:nowrap;margin-left:8px;text-align:right;}
.num{font-variant-numeric:tabular-nums;}

/* 内嵌标签 */
.ob-tag{display:inline-block;font-size:12px;padding:1px 8px;border-radius:4px;border:1px solid;line-height:1.6;margin-left:6px;}
.ob-tag.suc{color:#15A35B;border-color:#c2e7b0;background:#f0f9eb;}
.ob-tag.war{color:#E6A23C;border-color:#f5dab1;background:#fdf6ec;}

/* 缴费方式 */
.ob-pay{background:#fff;margin:12px;border-radius:8px;padding:14px;text-align:center;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-pay-h{text-align:center;}
.ob-qr-wrap{padding:8px 0;}
.ob-qr-ph{width:160px;height:160px;display:flex;align-items:center;justify-content:center;color:#c0c4cc;font-size:13px;}
.ob-pay-cap{font-size:13px;color:#606266;margin-top:4px;}
.ob-bank{font-size:13px;color:#606266;margin-top:8px;}
.ob-btn{display:block;width:100%;padding:12px;border:none;border-radius:24px;background:#2563EB;color:#fff;font-size:15px;margin-top:12px;cursor:pointer;transition:.15s;font-family:inherit;line-height:1.5;}
.ob-btn:hover{background:#1d4ed8;}

/* 辅助文字 */
.ob-foot{text-align:center;color:#909399;font-size:12px;padding:14px;line-height:1.7;}

/* 失效态 */
.ob-expired{background:#fff;margin:12px;border-radius:8px;padding:48px 18px 14px;text-align:center;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-expired-ic{width:64px;height:64px;border-radius:50%;background:#ecf3ff;color:#2563EB;font-size:32px;font-weight:800;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;}
.ob-expired-t{font-size:17px;font-weight:700;color:#303133;margin-bottom:10px;}
.ob-expired-d{font-size:13px;color:#909399;line-height:1.7;}
.ob-expired .ob-foot{margin-top:24px;}
</style>
