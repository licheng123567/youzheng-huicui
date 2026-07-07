<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { promiseStateLabel, payReqStatusLabel } from '../constants/enums'
// 分期状态：值含 PAID(已付款)走支付状态映射，其余兜底承诺状态映射；二者均回退原值不空白。
const instStatusLabel = (s?: string | null) => (s === 'PAID' ? payReqStatusLabel(s) : promiseStateLabel(s))

// M7 业主自助 H5：公开页(免登录)，对标原型 docs/ui/高保真/h5.html。
// 业主扫码/短信链接进入 → GET /pay/{token} 查账单。
// 结构：头部(小区·房号·业主脱敏·应缴合计) / 费用明细(减免拆分+物业费+滞纳金+缴费标准) /
//       分期计划(有则显示) / 收款方式(微信码+折叠对公账户+在线缴费敬请期待) / 有效期说明。
// 用原生 fetch(不走 authed client，public 端点无需 Bearer)。
const route = useRoute()
const token = String(route.params.token)
const bill = ref<any>(null)
const err = ref('')
const bankOpen = ref(false)   // 对公账户折叠态
const yuan = (c?: number | null) => (c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN'))

// 物业费本金 = 减免前应收合计 − 滞纳金拆分（未拆分时=合计，滞纳金行退政策文字）
const principalCents = computed<number | null>(() => {
  if (bill.value?.dueCents == null) return null
  if (bill.value?.penaltyCents == null) return bill.value.dueCents
  return bill.value.dueCents - bill.value.penaltyCents
})
// 欠费周期展示串："2025-01 ~ 2025-02（2个月）"
const periodText = computed<string>(() => {
  const p: string[] = bill.value?.arrearagePeriods ?? []
  if (!p.length) return ''
  return `${p[0]} ~ ${p[p.length - 1]}（${p.length}个月）`
})
// 滞纳金已被减免覆盖：有滞纳金拆分且减免额 ≥ 滞纳金（对应减免阶梯的"免滞纳金"档）→ 滞纳金划线 + 已减免标注
const penaltyWaived = computed<boolean>(() => {
  const b = bill.value
  return b?.penaltyCents != null && b.penaltyCents > 0 && (b.reductionCents ?? 0) >= b.penaltyCents
})

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
      <!-- 失效 / 错误态（不含任何业主信息） -->
      <div v-if="err" class="ob-expired">
        <div class="ob-expired-ic">!</div>
        <div class="ob-expired-t">{{ err }}</div>
        <div class="ob-expired-d">请联系物业管理处重新发送缴费链接。</div>
        <div class="ob-foot">有证慧催 · 安全缴费平台</div>
      </div>

      <template v-else-if="bill">
        <!-- 头部：品牌渐变 + 业主归属 + 应缴合计大字 -->
        <div class="ob-hd">
          <div class="ob-hd-nm">{{ bill.community }} · 物业费账单</div>
          <div class="ob-hd-owner">房号 {{ bill.room || '—' }} · 业主 {{ bill.ownerMasked || '—' }}</div>
          <div class="ob-hd-amt"><small>应缴合计 </small>{{ yuan(bill.payableCents) }}</div>
          <span v-if="bill.reductionCents" class="ob-reduce-tag">已减免 {{ yuan(bill.reductionCents) }}</span>
        </div>

        <!-- 费用明细 -->
        <div class="ob-sec">
          <h4 class="ob-sec-h">费用明细</h4>
          <div v-if="periodText" class="ob-li">
            <span>欠费周期</span>
            <span class="muted">{{ periodText }}</span>
          </div>
          <div class="ob-li">
            <span>物业费</span>
            <span class="r">{{ yuan(principalCents) }}</span>
          </div>
          <!-- 滞纳金：导入拆分了金额才显示；被减免覆盖时划线 + 已减免标注 -->
          <div v-if="bill.penaltyCents != null" class="ob-li">
            <span>滞纳金</span>
            <span v-if="penaltyWaived">
              <span class="orig">{{ yuan(bill.penaltyCents) }}</span>
              <span class="ob-tag suc">已减免</span>
            </span>
            <span v-else class="r">{{ yuan(bill.penaltyCents) }}</span>
          </div>
          <template v-if="bill.reductionCents">
            <div class="ob-li">
              <span>原应收</span>
              <span class="orig">{{ yuan(bill.dueCents) }}</span>
            </div>
            <div class="ob-li">
              <span>减免额{{ penaltyWaived ? '（滞纳金已减免）' : '' }}</span>
              <span class="reduce">−{{ yuan(bill.reductionCents) }}</span>
            </div>
            <div class="ob-li">
              <span>减免后应收（最终实缴）</span>
              <span class="r">{{ yuan(bill.payableCents) }}</span>
            </div>
          </template>
          <div v-if="bill.feeStd" class="ob-li">
            <span>缴费标准</span>
            <span class="muted">{{ bill.feeStd }}</span>
          </div>
        </div>

        <!-- 分期计划（BR-M7-06：协调员录入的承诺分期，只读） -->
        <div v-if="bill.installments?.length" class="ob-sec">
          <h4 class="ob-sec-h">分期计划</h4>
          <div v-for="(it, i) in bill.installments" :key="i" class="ob-li">
            <span>{{ it.period }} · 到期 {{ it.dueDate }}</span>
            <span>
              <span class="num">{{ yuan(it.amountCents) }}</span>
              <span class="ob-tag" :class="it.status==='PAID' ? 'suc' : 'war'" :title="it.status">{{ instStatusLabel(it.status) }}</span>
            </span>
          </div>
        </div>

        <!-- 收款方式（线下缴费） -->
        <div class="ob-pay">
          <h4 class="ob-sec-h ob-pay-h">收款方式（线下缴费）</h4>
          <div v-if="bill.payChannels?.wechatQr" class="ob-qr-wrap">
            <el-image :src="bill.payChannels.wechatQr" style="width:160px;height:160px" fit="contain">
              <template #error><div class="ob-qr-ph">微信收款码</div></template>
            </el-image>
            <div class="ob-pay-cap">微信扫码缴费</div>
          </div>
          <template v-if="bill.payChannels?.bankAccount">
            <button class="ob-btn-ghost" type="button" :aria-expanded="bankOpen" @click="bankOpen = !bankOpen">
              {{ bankOpen ? '收起对公账户' : '查看对公账户' }}
            </button>
            <div v-if="bankOpen" class="ob-bank-detail">
              <div>对公账户：{{ bill.payChannels.bankAccount }}</div>
              <div class="ob-bank-memo">转账备注请填写：<b>房号 {{ bill.room || '—' }}</b></div>
            </div>
          </template>
          <button class="ob-btn" type="button" :disabled="!bill.onlinePay" :aria-disabled="!bill.onlinePay">
            {{ bill.onlinePay ? '在线支付' : '在线缴费（敬请期待）' }}
          </button>
        </div>

        <div class="ob-note">本链接有效期 7 天，过期请联系物业重发；可复制转发微信。</div>
        <div class="ob-foot">有证慧催 · 业主无需登录　|　缴费后由物业核对到账</div>
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
.ob-hd-owner{font-size:13px;opacity:.9;line-height:1.7;margin-top:4px;}
.ob-hd-amt{font-size:34px;font-weight:800;margin-top:8px;line-height:1.2;font-variant-numeric:tabular-nums;}
.ob-hd-amt small{font-size:16px;font-weight:400;opacity:.9;}
.ob-reduce-tag{display:inline-block;margin-top:10px;font-size:12px;padding:2px 9px;border-radius:4px;background:rgba(255,255,255,.18);color:#fff;}

/* 卡片小节 */
.ob-sec{background:#fff;margin:12px;border-radius:8px;padding:14px;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-sec-h{margin:0 0 10px;font-size:14px;font-weight:600;color:#303133;}

/* 明细行 */
.ob-li{display:flex;justify-content:space-between;align-items:center;padding:7px 0;border-bottom:1px solid #ebeef5;font-size:14px;line-height:1.6;color:#606266;gap:8px;}
.ob-li:last-child{border:none;}
.ob-li .r{color:#F56C6C;font-weight:600;white-space:nowrap;}
.ob-li .orig{color:#909399;text-decoration:line-through;white-space:nowrap;}
.ob-li .reduce{color:#15A35B;font-weight:600;white-space:nowrap;}
.ob-li .muted{color:#909399;text-align:right;font-size:12px;max-width:60%;}
.num{font-variant-numeric:tabular-nums;}

/* 内嵌标签 */
.ob-tag{display:inline-block;font-size:12px;padding:1px 8px;border-radius:4px;border:1px solid;line-height:1.6;margin-left:6px;}
.ob-tag.suc{color:#15A35B;border-color:#c2e7b0;background:#f0f9eb;}
.ob-tag.war{color:#E6A23C;border-color:#f5dab1;background:#fdf6ec;}

/* 收款方式 */
.ob-pay{background:#fff;margin:12px;border-radius:8px;padding:14px;text-align:center;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-pay-h{text-align:center;}
.ob-qr-wrap{padding:8px 0;}
.ob-qr-ph{width:160px;height:160px;display:flex;align-items:center;justify-content:center;color:#c0c4cc;font-size:13px;}
.ob-pay-cap{font-size:13px;color:#606266;margin-top:4px;}
.ob-btn-ghost{display:block;width:100%;padding:10px;border:1px solid #dcdfe6;border-radius:24px;background:#fff;color:#2563EB;font-size:14px;margin-top:10px;cursor:pointer;transition:.15s;font-family:inherit;line-height:1.5;}
.ob-btn-ghost:hover{border-color:#2563EB;}
.ob-bank-detail{background:#f8fafc;border:1px solid #ebeef5;border-radius:8px;padding:10px 14px;margin-top:8px;text-align:left;font-size:13px;line-height:2;color:#606266;}
.ob-bank-memo{color:#909399;font-size:12px;margin-top:4px;}
.ob-btn{display:block;width:100%;padding:12px;border:none;border-radius:24px;background:#2563EB;color:#fff;font-size:15px;margin-top:12px;cursor:pointer;transition:.15s;font-family:inherit;line-height:1.5;}
.ob-btn:hover{background:#1d4ed8;}
.ob-btn:disabled{background:#eef1f6;color:#c0c4cc;cursor:not-allowed;}

/* 辅助文字 */
.ob-note{text-align:center;color:#909399;font-size:12px;padding:0 14px;line-height:1.7;}
.ob-foot{text-align:center;color:#909399;font-size:12px;padding:14px;line-height:1.7;}

/* 失效态 */
.ob-expired{background:#fff;margin:12px;border-radius:8px;padding:48px 18px 14px;text-align:center;box-shadow:0 1px 4px rgba(20,40,90,.04);}
.ob-expired-ic{width:64px;height:64px;border-radius:50%;background:#ecf3ff;color:#2563EB;font-size:32px;font-weight:800;display:flex;align-items:center;justify-content:center;margin:0 auto 20px;}
.ob-expired-t{font-size:17px;font-weight:700;color:#303133;margin-bottom:10px;}
.ob-expired-d{font-size:13px;color:#909399;line-height:1.7;}
.ob-expired .ob-foot{margin-top:24px;}
</style>
