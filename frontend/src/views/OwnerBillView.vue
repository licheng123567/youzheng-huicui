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
  <div style="max-width:420px;margin:0 auto;padding:16px;min-height:100vh;background:#f5f7fa">
    <div style="text-align:center;padding:16px 0">
      <h2 style="margin:0;color:#1f2d3d">物业费缴纳</h2>
      <div style="color:#909399;font-size:13px;margin-top:4px">有证慧催 · 业主自助</div>
    </div>
    <el-alert v-if="err" :title="err" type="error" :closable="false" show-icon />
    <template v-else-if="bill">
      <el-card>
        <div style="text-align:center;padding:8px 0">
          <div style="color:#909399;font-size:13px">{{ bill.community }} · 应缴金额</div>
          <div style="font-size:34px;font-weight:700;color:#e6a23c;margin:8px 0">{{ yuan(bill.payableCents) }}</div>
          <el-tag v-if="bill.reductionCents" type="success" size="small">已减免 {{ yuan(bill.reductionCents) }}</el-tag>
        </div>
        <el-divider style="margin:10px 0" />
        <div v-if="bill.arrearagePeriods?.length" style="font-size:13px;color:#606266">
          欠费账期：{{ bill.arrearagePeriods.join('、') }}
        </div>
        <div v-if="bill.feeStd" style="font-size:13px;color:#606266;margin-top:4px">收费标准：{{ bill.feeStd }}</div>
      </el-card>

      <el-card v-if="bill.installments?.length" style="margin-top:12px" header="分期明细">
        <div v-for="(it, i) in bill.installments" :key="i" style="display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #f0f0f0">
          <span>{{ it.period }} · 到期 {{ it.dueDate }}</span>
          <span>{{ yuan(it.amountCents) }} <el-tag size="small" :type="it.status==='PAID'?'success':'warning'">{{ it.status }}</el-tag></span>
        </div>
      </el-card>

      <el-card style="margin-top:12px" header="缴费方式">
        <div v-if="bill.payChannels?.wechatQr" style="text-align:center;padding:8px">
          <el-image :src="bill.payChannels.wechatQr" style="width:160px;height:160px" fit="contain"><template #error><div style="padding:40px;color:#c0c4cc">微信收款码</div></template></el-image>
          <div style="font-size:13px;color:#606266">微信扫码缴费</div>
        </div>
        <div v-if="bill.payChannels?.bankAccount" style="font-size:13px;color:#606266;margin-top:8px">对公账户：{{ bill.payChannels.bankAccount }}</div>
        <el-button v-if="bill.onlinePay" type="primary" style="width:100%;margin-top:12px">在线支付</el-button>
      </el-card>
      <p style="text-align:center;color:#c0c4cc;font-size:12px;margin-top:16px">如有疑问请联系物业 · 本页面由有证慧催提供</p>
    </template>
    <el-skeleton v-else :rows="5" animated />
  </div>
</template>
