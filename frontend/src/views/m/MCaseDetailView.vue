<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../../stores/auth'
import { api } from '../../api/client'
import { promiseStateLabel, ticketStatusLabel } from '../../constants/enums'

// 移动案件作业台:详情 + 角色化动作(拨号提示/取最新录音→AI复盘/登记承诺/转工单/标记回款/发缴费链接/建议法务)。
// 动作请求体与 PC CaseDetailView 完全一致,复用同一批后端端点。
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const id = String(route.params.id)
const role = computed(() => auth.me?.role ?? '')

const c = ref<any>(null)
const promises = ref<any[]>([])
const tickets = ref<any[]>([])
const loading = ref(true)
const yuan = (cents?: number) => (cents == null ? '—' : '¥' + (cents / 100).toLocaleString('zh-CN'))

const STATUS_LABEL: Record<string, string> = {
  PENDING_DISPATCH: '待派单', PROVIDER_SEA: '服务商公海', IN_PROGRESS: '催收中',
  PROMISED: '已承诺', SETTLED: '已结清', WITHDRAWN: '已撤回', BAD_DEBT: '坏账', VOIDED: '已作废',
}
const statusLabel = (s?: string) => STATUS_LABEL[s ?? ''] ?? s ?? '—'
const dueCents = computed(() => c.value?.dueCents ?? c.value?.reducedCents ?? 0)
const phone = computed(() => c.value?.phone ?? c.value?.contacts?.find((x: any) => x.isPrimary)?.phone ?? c.value?.contacts?.[0]?.phone ?? '—')
const months = computed(() => c.value?.overdueMonths ?? c.value?.months ?? '—')

// 动作面板状态机(对齐原型 ov):null|callhint|fetch|review|promise|ticket|repay
const panel = ref<string | null>(null)
const busy = ref(false)
const tmsg = ref('')
let tt: any
function toast(m: string) { tmsg.value = m; clearTimeout(tt); tt = setTimeout(() => (tmsg.value = ''), 1800) }

// 表单(预填欠费金额)
const today = () => new Date(Date.now()).toISOString().slice(0, 10)
const fPromise = ref<any>({ date: '', amountYuan: 0 })
const fTicket = ref<any>({ type: '上门核实', note: '' })
const fRepay = ref<any>({ amountYuan: 0, channel: 'WECHAT_QR', paidAt: '' })

// 取最新录音 + AI 复盘
const fetchState = ref<'loading' | 'ready' | 'none'>('loading')
const rec = ref<any>(null)
const review = ref<any>(null)

async function load() {
  loading.value = true
  const [r, p, t] = await Promise.all([
    api.GET('/cases/{id}', { params: { path: { id } } as any }),
    api.GET('/cases/{id}/promises', { params: { path: { id } } as any }),
    api.GET('/cases/{id}/tickets', { params: { path: { id } } as any }),
  ])
  c.value = (r as any).data ?? null
  promises.value = ((p as any).data?.items ?? (p as any).data) ?? []
  tickets.value = ((t as any).data?.items ?? (t as any).data) ?? []
  loading.value = false
}

function openPromise() { fPromise.value = { date: today(), amountYuan: dueCents.value / 100 }; panel.value = 'promise' }
function openTicket() { fTicket.value = { type: '上门核实', note: '' }; panel.value = 'ticket' }
function openRepay() { fRepay.value = { amountYuan: dueCents.value / 100, channel: 'WECHAT_QR', paidAt: today() }; panel.value = 'repay' }

async function submitPromise() {
  busy.value = true
  const { error } = await api.POST('/cases/{id}/promises', { params: { path: { id } }, body: { date: fPromise.value.date, amountCents: Math.round(fPromise.value.amountYuan * 100) } as any })
  busy.value = false
  if (error) return toast('登记失败：' + ((error as any)?.message ?? ''))
  panel.value = null; toast('已登记承诺，到期将提醒'); load()
}
async function submitTicket() {
  busy.value = true
  const { error } = await api.POST('/cases/{id}/tickets', { params: { path: { id } }, body: { type: fTicket.value.type, note: fTicket.value.note } as any })
  busy.value = false
  if (error) return toast('转工单失败：' + ((error as any)?.message ?? ''))
  panel.value = null; toast('已转工单（' + fTicket.value.type + '）给协调员'); load()
}
async function submitRepay() {
  busy.value = true
  const { error } = await api.POST('/cases/{id}/repay-lines', { params: { path: { id } }, body: { amountCents: Math.round(fRepay.value.amountYuan * 100), channel: fRepay.value.channel, paidAt: fRepay.value.paidAt } as any })
  busy.value = false
  if (error) return toast('标记失败：' + ((error as any)?.message ?? ''))
  panel.value = null; toast('已标注线下回款 → 触发结清判定'); load()
}
async function sendPayLink() {
  busy.value = true
  const { error } = await api.POST('/cases/{id}/pay-links', { params: { path: { id } }, body: { channel: 'SMS' } as any })
  busy.value = false
  toast(error ? '发送失败：' + ((error as any)?.message ?? '') : '已发送缴费链接（短信）')
}
async function suggestLegal() {
  busy.value = true
  const { error } = await api.POST('/cases/{id}/follow-ups', { params: { path: { id } }, body: { content: '【建议走法务】催收员建议本案进入法务程序（轻标·待协调员审）', method: 'OTHER' } as any })
  busy.value = false
  toast(error ? '建议失败' : '已轻标"建议走法务"（记入跟进）')
}
async function handleFirstTicket() {
  const pend = tickets.value.find((x: any) => x.status === 'PENDING')
  if (!pend) return toast('暂无待处理工单')
  busy.value = true
  const { error } = await api.POST('/tickets/{id}/handle', { params: { path: { id: String(pend.id) } }, body: { result: '已上门核实', receipt: '移动端处理回执' } as any })
  busy.value = false
  if (error) return toast('处理失败：' + ((error as any)?.message ?? ''))
  toast('工单已回执 → 互推闭环'); load()
}

// 取最新通话录音 + AI 复盘
async function startFetch() {
  panel.value = 'fetch'; fetchState.value = 'loading'; rec.value = null; review.value = null
  const { data, error } = await api.GET('/cases/{id}/recordings/latest', { params: { path: { id } } as any })
  if (error || !data) { fetchState.value = 'none'; return }
  rec.value = data; fetchState.value = 'ready'
}
async function openReview() {
  if (!rec.value?.id) return
  panel.value = 'review'
  const { data } = await api.GET('/recordings/{id}/ai-review', { params: { path: { id: String(rec.value.id) } } as any })
  review.value = data ?? null
}
const dialogue = computed<any[]>(() => {
  const d = review.value?.dialogue
  if (Array.isArray(d)) return d
  try { return JSON.parse(d) } catch { return [] }
})

function callHint() { panel.value = 'callhint' }
onMounted(load)
</script>

<template>
  <div>
    <div v-if="loading" class="mini" style="text-align:center;padding:30px 0">加载中…</div>

    <template v-else-if="c">
      <!-- 概览三宫格 -->
      <div class="row" style="margin-bottom:8px">
        <b style="font-size:16px">{{ c.ownerName || '—' }}<template v-if="c.room"> · {{ c.room }}</template></b>
        <span class="badge b-bl">{{ statusLabel(c.status) }}</span>
      </div>
      <div class="info3" style="margin-bottom:12px">
        <div><div class="v due">{{ yuan(dueCents) }}</div><div class="k">欠费金额</div></div>
        <div><div class="v">{{ months }}</div><div class="k">欠费月数</div></div>
        <div><div class="v" style="font-size:13px">{{ phone }}</div><div class="k">联系电话</div></div>
      </div>

      <!-- 主操作 -->
      <button class="mbtn pri" @click="callHint">📞 拨打（本机通话 · 系统录音）</button>
      <button class="mbtn gho" style="margin-top:8px" @click="startFetch">🎧 获取最新通话录音</button>

      <!-- 催收员操作区 -->
      <template v-if="role === 'CO'">
        <div class="sec">操作区</div>
        <div class="tagpick">
          <span class="p" @click="sendPayLink">🔗 发缴费链接</span>
          <span class="p" @click="openTicket">🛠 转工单</span>
          <span class="p" @click="openPromise">📅 登记承诺</span>
          <span class="p" @click="suggestLegal">⚖ 建议法务</span>
        </div>
        <div class="mini" style="margin-top:6px">转工单 = 推物业协调员上门/核实（互推闭环，处理后回执给你）。</div>
      </template>

      <!-- 协调员操作 -->
      <template v-if="role === 'PC'">
        <button class="mbtn suc" style="margin-top:8px" @click="openRepay">💰 标记线下回款</button>
        <button class="mbtn gho" style="margin-top:8px" @click="handleFirstTicket">🛠 处理工单</button>
      </template>

      <!-- 时间线(承诺/工单) -->
      <div class="sec">跟进时间线</div>
      <div class="mc">
        <div class="tl" v-if="promises.length || tickets.length">
          <div class="e" v-for="p in promises" :key="'p' + p.id">
            <span class="tm">{{ p.date }}</span> 登记承诺 {{ yuan(p.amountCents) }}
            <div class="mini" :title="p.state || p.status">状态：{{ (p.state || p.status) ? promiseStateLabel(p.state || p.status) : '—' }}</div>
          </div>
          <div class="e" v-for="t in tickets" :key="'t' + t.id">
            <span class="tm">工单</span> {{ t.type }} · <span :title="t.status">{{ ticketStatusLabel(t.status) }}</span>
            <div class="mini" v-if="t.note">{{ t.note }}</div>
          </div>
        </div>
        <div class="mini" v-else>暂无承诺/工单记录</div>
      </div>
    </template>

    <div v-else class="mini" style="text-align:center;padding:30px 0">案件不存在或无权访问</div>

    <!-- ===== 全屏面板 ===== -->
    <!-- 拨号提示 -->
    <div class="m-ov dial" v-if="panel === 'callhint'">
      <div class="av-lg">{{ (c?.ownerName || '?').charAt(0) }}</div>
      <div class="nm-lg">{{ c?.ownerName }}</div>
      <div class="mini" style="color:rgba(255,255,255,.7);margin-top:4px">{{ c?.room }} · {{ phone }}</div>
      <div class="glass">欠费 {{ yuan(dueCents) }} · {{ months }} 个月</div>
      <div class="glass" style="margin-top:10px;font-size:12px;line-height:1.7;text-align:left">将跳起<b>本机拨号器</b>由你拨打，通话在本机进行、<b>系统录音</b>。通话结束后点「获取最新通话录音」查看上传解析状态；机型不支持时可手动上传。</div>
      <button class="mbtn suc" style="max-width:260px;margin-top:18px" @click="panel = null; toast('已跳起本机拨号器（演示）')">跳起拨号器拨打</button>
      <button class="mbtn gho" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.6);max-width:260px;margin-top:10px" @click="startFetch">通话已结束 → 查最近一通录音</button>
      <button class="mbtn gho" style="background:transparent;color:rgba(255,255,255,.7);border:none;max-width:260px;margin-top:6px" @click="panel = null">取消</button>
    </div>

    <!-- 取最新录音 -->
    <div class="m-ov" v-if="panel === 'fetch'">
      <header class="m-ab"><span class="m-back" @click="panel = null">‹ 最近一通通话录音</span></header>
      <div class="m-body">
        <template v-if="fetchState === 'loading'">
          <div style="text-align:center;padding:30px 0">
            <div class="ring">🎧</div>
            <div class="sec" style="text-align:center">正在检测最近一通的录音与解析状态…</div>
          </div>
        </template>
        <template v-else-if="fetchState === 'ready'">
          <div class="mc">
            <div class="row"><b>最近一通 · 录音状态</b><span class="badge" :class="rec?.status === 'READY' ? 'b-gr' : 'b-or'">{{ rec?.status === 'READY' ? '已就绪' : (rec?.status || '处理中') }}</span></div>
            <div class="info3" style="margin-top:10px">
              <div><div class="v" style="font-size:13px">{{ (rec?.recordedAt || '').slice(5, 16) || '—' }}</div><div class="k">时间</div></div>
              <div><div class="v" style="font-size:13px">{{ rec?.durationSec != null ? rec.durationSec + 's' : '—' }}</div><div class="k">时长</div></div>
              <div><div class="v" style="font-size:13px">{{ rec?.phone || phone }}</div><div class="k">号码</div></div>
            </div>
          </div>
          <button class="mbtn pri" style="margin-top:8px" :disabled="rec?.status !== 'READY'" @click="openReview">查看 AI 复盘</button>
        </template>
        <template v-else>
          <div class="mc" style="background:#fff7ed">
            <b style="font-size:13px">本次通话没有录音上来</b>
            <div class="mini" style="margin:6px 0 8px">机型不支持/未授自动录音/无文件时，可手动选择录音文件上传（同一解析链路）。</div>
            <button class="mbtn gho" @click="toast('已打开手动上传入口（演示）')">手动上传录音文件</button>
          </div>
        </template>
        <button class="mbtn gho" style="margin-top:8px" @click="panel = null">返回案件</button>
      </div>
    </div>

    <!-- AI 复盘 -->
    <div class="m-ov" v-if="panel === 'review'">
      <header class="m-ab"><span class="m-back" @click="panel = null">‹ AI 复盘</span></header>
      <div class="m-body">
        <div class="mc" style="background:#eff6ff;color:#1d4ed8;font-size:13px" v-if="review?.summary">解析摘要：{{ review.summary }}</div>
        <div class="sec">① 对话记录</div>
        <div class="mc">
          <div class="tr-prev" v-if="dialogue.length">
            <div class="b" v-for="(d, i) in dialogue" :key="i" :style="{ background: (d.role === '业主' || d.speaker === '业主') ? '#f3f4f6' : '#eff6ff' }">
              <div class="who" :style="{ color: (d.role === '业主' || d.speaker === '业主') ? '#6b7280' : '#2563eb' }">{{ d.role || d.speaker || '对话' }}</div>
              {{ d.text || d.content || d }}
            </div>
          </div>
          <div class="mini" v-else>暂无对话记录</div>
        </div>
        <div class="sec">② 质检风险</div>
        <div class="mc"><div class="mini" style="line-height:1.7">{{ review?.risks || '无风险记录' }}</div></div>
        <div class="sec">③ 下一步建议</div>
        <div class="mc" style="border-left:3px solid var(--primary)"><div class="mini" style="line-height:1.7;color:#374151">{{ review?.suggestions || '暂无建议' }}</div></div>
        <button class="mbtn pri" style="margin-top:12px" @click="panel = null; toast('已查看复盘')">完成</button>
      </div>
    </div>

    <!-- 登记承诺 -->
    <div class="m-ov" v-if="panel === 'promise'">
      <header class="m-ab"><span class="m-back" @click="panel = null">‹ 登记承诺</span></header>
      <div class="m-body">
        <div class="mc"><div class="mini">登记业主口头承诺；到期前经工作台待办 + App 推送提醒（不发短信省成本）。</div></div>
        <div class="sec">承诺缴费日期</div>
        <input class="inp" type="date" style="width:100%" v-model="fPromise.date" />
        <div class="sec">承诺金额（元）</div>
        <input class="inp" type="number" style="width:100%" v-model.number="fPromise.amountYuan" />
        <button class="mbtn pri" style="margin-top:12px" :disabled="busy" @click="submitPromise">保存承诺</button>
        <button class="mbtn gho" style="margin-top:8px" @click="panel = null">取消</button>
      </div>
    </div>

    <!-- 转工单 -->
    <div class="m-ov" v-if="panel === 'ticket'">
      <header class="m-ab"><span class="m-back" @click="panel = null">‹ 转工单</span></header>
      <div class="m-body">
        <div class="mc"><div class="mini" style="line-height:1.7">转工单给<b>物业协调员</b>上门/核实（互推闭环）；处理后回执给你，不改案件状态。</div></div>
        <div class="sec">工单类型</div>
        <div class="tagpick">
          <span v-for="ty in ['上门核实', '质量维修', '信息核实']" :key="ty" class="p" :class="{ on: fTicket.type === ty }" @click="fTicket.type = ty">{{ ty }}</span>
        </div>
        <div class="sec">说明</div>
        <textarea class="ta2" v-model="fTicket.note" placeholder="如：业主称房屋漏水，请上门核实并反馈"></textarea>
        <button class="mbtn pri" style="margin-top:12px" :disabled="busy" @click="submitTicket">提交工单</button>
        <button class="mbtn gho" style="margin-top:8px" @click="panel = null">取消</button>
      </div>
    </div>

    <!-- 标记线下回款 -->
    <div class="m-ov" v-if="panel === 'repay'">
      <header class="m-ab"><span class="m-back" @click="panel = null">‹ 标记线下回款</span></header>
      <div class="m-body">
        <div class="mc"><div class="mini" style="line-height:1.7">业主据缴费链接展示的<b>物业收款渠道线下缴费</b>，核实后在此标注；标注后回款更新、<b>触发结清判定</b>。</div></div>
        <div class="sec">回款金额（元）</div>
        <input class="inp" type="number" style="width:100%" v-model.number="fRepay.amountYuan" />
        <div class="sec">缴费日期</div>
        <input class="inp" type="date" style="width:100%" v-model="fRepay.paidAt" />
        <div class="sec">缴费渠道</div>
        <div class="tagpick">
          <span v-for="ch in ['WECHAT_QR', 'BANK_TRANSFER', 'CASH']" :key="ch" class="p" :class="{ on: fRepay.channel === ch }" @click="fRepay.channel = ch">{{ ({ WECHAT_QR: '微信收款码', BANK_TRANSFER: '对公转账', CASH: '现金' } as any)[ch] }}</span>
        </div>
        <button class="mbtn pri" style="margin-top:12px" :disabled="busy" @click="submitRepay">确认标注回款</button>
        <button class="mbtn gho" style="margin-top:8px" @click="panel = null">取消</button>
      </div>
    </div>

    <div class="m-toast" v-if="tmsg">{{ tmsg }}</div>
  </div>
</template>
