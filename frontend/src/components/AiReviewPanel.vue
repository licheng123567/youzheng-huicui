<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import RecordingAudioPlayer from './RecordingAudioPlayer.vue'
import { downloadAuthedFile } from '../utils/download'

// AI 复盘面板（对标原型 index.html §AI 复盘抽屉 .reviewpanel，样式已在 ds-admin.css）：
// 右侧滑出、可拖宽；左=对话记录(ASR 说话人分离气泡)，右=通话小结+通话结果标记(tag-grid)+质检风险+下一步建议；
// 底部=存证本次录音(case.evidence) / 关闭 / 保存标注。所有角色的录音复盘统一走本面板。
// 建议卡「采纳」emit('adopt', card) 交调用方联动动作弹窗（案件三栏内）；无处理器的调用方（通话记录页）不传即不渲染采纳钮。
const props = withDefaults(defineProps<{
  open: boolean
  recordingId: string
  caseId?: string
  ownerName?: string
  room?: string
  markCodes?: Array<{ code: string; label: string; connected?: boolean }>
  canAdopt?: boolean
}>(), { canAdopt: false })
const emit = defineEmits<{ 'update:open': [v: boolean]; adopt: [card: any] }>()
const auth = useAuth()

const rec = ref<any>(null)
const review = ref<any>(null)
const loading = ref(false)
const mark = ref('')
const evidenced = ref(false)
const evidenceId = ref('')   // 发起存证返回的存证记录 id（供下载备案证书）
const dismissed = ref<Record<string, boolean>>({})

// 结果标记码：优先调用方传入（CaseDetail.markCodes SSOT），缺省回退内置五码（对齐 CFG-MARK-CODES）
const MARK_FALLBACK = [
  { code: 'PROMISED', label: '已承诺', connected: true },
  { code: 'REFUSED', label: '拒接/拒还', connected: true },
  { code: 'NEED_TICKET', label: '需转工单', connected: true },
  { code: 'FOLLOW_UP', label: '待跟进', connected: true },
  { code: 'NO_ANSWER', label: '无人接听', connected: false },
]
// 标记码按角色对齐职能：「需转工单(NEED_TICKET)」是催收员向物业协调员发起工单的动作，
// 物业侧(PL/PC)本身是工单处理方、不发起，故非催收员剔除该项（对标用户反馈）。
const codes = computed(() => {
  const base = props.markCodes?.length ? props.markCodes : MARK_FALLBACK
  if (auth.me?.role === 'CO') return base
  return base.filter((c) => c.code !== 'NEED_TICKET')
})
const markConnected = computed<boolean | null>(() => {
  if (!mark.value) return null
  const m = codes.value.find((c) => c.code === mark.value)
  return m?.connected !== false
})
const cards = computed<any[]>(() => (review.value?.suggestions ?? []).filter((s: any) => !dismissed.value[String(s.id)]))

async function load() {
  if (!props.recordingId) return
  loading.value = true
  rec.value = null; review.value = null; mark.value = ''; evidenced.value = false; evidenceId.value = ''; dismissed.value = {}
  const [r, v] = await Promise.all([
    api.GET('/recordings/{id}', { params: { path: { id: props.recordingId } } } as any),
    api.GET('/recordings/{id}/ai-review', { params: { path: { id: props.recordingId } } } as any),
  ])
  loading.value = false
  rec.value = (r as any).data ?? null
  if ((v as any).error) { ElMessage.error('复盘加载失败（录音可能未解析完成）'); return }
  review.value = (v as any).data
}
watch(() => [props.open, props.recordingId], ([o]) => { if (o) load() }, { immediate: true })

function close() { emit('update:open', false) }

async function saveMark() {
  if (!mark.value) { ElMessage.warning('请先选择通话结果标记'); return }
  const { error } = await api.POST('/recordings/{id}/ai-review', { params: { path: { id: props.recordingId } }, body: { mark: mark.value } as any })
  if (error) { ElMessage.error('标注失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success(markConnected.value ? '已保存标注（接通有效跟进 · 重置释放计时）' : '已保存标注（未接通 · 仅留痕）')
  close()
}

// 存证本次录音（M6·易保全哈希保全）：scene=RECORDING + refIds=[本录音]。
// 后端对录音字节做 SHA-512 送易保全出证；备案证书/链上信息约 10 分钟后就绪（后台轮询回填）。
async function doEvidence() {
  if (!props.caseId) return
  const { data, error } = await api.POST('/cases/{id}/evidence', { params: { path: { id: props.caseId } }, body: { scene: 'RECORDING', refIds: [String(props.recordingId)] } as any })
  if (error) { ElMessage.error('存证失败：' + ((error as any)?.message ?? '')); return }
  evidenced.value = true
  evidenceId.value = String((data as any)?.id ?? '')
  ElMessage.success('已发起录音存证（送易保全备案 · 约10分钟出证书）')
}

// 下载存证文件（本通录音音频，走我方鉴权流端点）
function downloadEvidenceFile() {
  downloadAuthedFile(`/v1/recordings/${props.recordingId}/audio`, `存证录音_${props.recordingId}.mp3`, '该录音暂无音频文件。')
}
// 下载存证证书（代理易保全 downPreservationCert；未就绪→409 提示稍后）
function downloadCert() {
  if (!evidenceId.value) { ElMessage.warning('请先发起存证'); return }
  downloadAuthedFile(`/v1/evidence/${evidenceId.value}/certificate`, `存证证书_${evidenceId.value}.zip`)
}

function dismiss(c: any) { dismissed.value[String(c.id)] = true; ElMessage.info('已忽略该建议') }
function adopt(c: any) { emit('adopt', c) }

// 拖宽（对标原型 startReviewResize；同 DsDrawer 手感）
const width = ref(1040)
const resizing = ref(false)
function startResize(e: PointerEvent) {
  resizing.value = true
  const startX = e.clientX, startW = width.value
  const maxW = Math.min(1400, window.innerWidth * 0.98)
  function onMove(ev: PointerEvent) { width.value = Math.min(maxW, Math.max(560, startW + (startX - ev.clientX))) }
  function onUp() {
    resizing.value = false
    document.removeEventListener('pointermove', onMove); document.removeEventListener('pointerup', onUp)
    document.body.style.userSelect = ''; document.body.style.cursor = ''
  }
  document.body.style.userSelect = 'none'; document.body.style.cursor = 'col-resize'
  document.addEventListener('pointermove', onMove); document.addEventListener('pointerup', onUp)
}

const durText = computed(() => {
  const s = rec.value?.durationSec
  if (s == null) return ''
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})
</script>

<template>
  <Teleport to="body">
    <div class="mask" :class="{ on: open }" @click.self="close" style="z-index:1000"></div>
    <div class="reviewpanel" :class="{ on: open }" :style="{ width: width + 'px' }" role="dialog" aria-modal="true" aria-label="AI 复盘 · 本次录音">
      <div class="rp-resize" :class="{ dragging: resizing }" @pointerdown.prevent="startResize" title="拖动调节宽度"></div>
      <div class="rp-head">
        <div class="rp-title">🧠 AI 复盘 · 本次录音
          <span class="note" style="font-weight:400;margin-left:6px">
            <template v-if="ownerName">{{ ownerName }}<template v-if="room"> · {{ room }}</template></template>
            <template v-if="durText"> · {{ durText }}</template>
          </span>
        </div>
        <span class="x" role="button" tabindex="0" aria-label="关闭" style="margin-left:auto" @click="close" @keydown.enter="close">×</span>
      </div>

      <div class="rp-body">
        <!-- 左：对话记录（ASR 转写·说话人分离） -->
        <div class="rp-left">
          <!-- 回听录音音频（全角色 · BR-M4-01b） -->
          <div class="sec-title" style="margin-top:0">录音回放</div>
          <RecordingAudioPlayer v-if="recordingId" :recording-id="recordingId" :file-name="ownerName ? (ownerName + (room ? '_' + room : '')) : ''" />
          <div class="sec-title">对话记录 · ASR 转写（说话人分离）</div>
          <div v-if="loading" class="note" style="padding:24px 0;text-align:center">载入中…</div>
          <div v-else-if="review?.dialogue?.length" class="chat">
            <div v-for="(m, i) in review.dialogue" :key="i" class="row" :class="(m.speaker === 'AGENT' || m.speaker === '催收员' || m.speaker === '协调员') ? 'me' : 'them'">
              <div>
                <div class="bub" style="white-space:pre-wrap">{{ m.text }}</div>
                <div class="meta">{{ m.speaker }}</div>
              </div>
            </div>
          </div>
          <div v-else-if="rec?.transcript" style="white-space:pre-wrap;background:#f7f9fc;border:1px solid var(--bd);padding:12px;border-radius:6px;font-size:13px;line-height:1.8">{{ rec.transcript }}</div>
          <div v-else class="note">暂无转写内容（录音未解析完成或解析失败）。</div>
        </div>

        <!-- 右：AI 复盘（小结 / 结果标记 / 质检风险 / 下一步建议） -->
        <div class="rp-right">
          <div class="bgbox" style="margin-top:0">📋 {{ review?.summary || '（暂无通话小结）' }}</div>

          <div class="sec-title">通话结果标记</div>
          <div class="tag-grid">
            <span v-for="c in codes" :key="c.code" class="tag-pick" :class="{ on: mark === c.code, dgp: c.code === 'REFUSED' }" @click="mark = c.code">{{ c.label }}</span>
          </div>
          <div v-if="mark" style="margin:8px 0">
            <span v-if="markConnected" class="tag suc" style="font-size:11px">接通有效跟进 · 重置释放计时</span>
            <span v-else class="tag inf" style="font-size:11px">未接通 · 仅留痕</span>
          </div>

          <div class="sec-title">质检风险点</div>
          <template v-if="review?.risks?.length">
            <div v-for="(r, ri) in review.risks" :key="ri" class="riskbar" :class="(r.level === 'L2' || r.level === 'HIGH') ? 'l2' : 'l1'">
              {{ (r.level === 'L2' || r.level === 'HIGH') ? '🔴' : '⚠️' }} {{ r.level }}：{{ r.desc }}<span v-if="r.segmentTs" style="color:var(--sec)"> @{{ r.segmentTs }}</span>
            </div>
          </template>
          <div v-else class="note" style="font-size:12px">本次通话未检出风险。</div>

          <div class="sec-title">下一步建议</div>
          <div v-for="c in cards" :key="c.id" class="aicard" :class="c.actionRef === 'PROMISE' ? 'script' : 'obj'">
            <div class="h">
              <span>💡 {{ c.type || '策略建议' }}</span>
              <span v-if="c.confidence" class="tag pri">置信度 {{ c.confidence }}</span>
            </div>
            <div class="ti">{{ c.title }}</div>
            <div class="tx">{{ c.body }}</div>
            <div v-if="c.trigger" style="margin-top:6px"><span class="tag war" style="font-size:11px">{{ c.trigger }}</span></div>
            <div class="cta">
              <el-button v-if="canAdopt && c.actionRef && c.actionRef !== 'NONE'" size="small" type="primary" @click="adopt(c)">✓ 采纳</el-button>
              <el-button size="small" @click="dismiss(c)">✗ 忽略</el-button>
            </div>
          </div>
          <div v-if="!cards.length" class="note" style="font-size:12px">复盘建议已处理完毕。</div>
        </div>
      </div>

      <div class="rp-foot">
        <!-- 存证本次录音（M6·易保全）：有存证权 + 录音 READY 才可发起；发起后可下载存证文件/证书 -->
        <template v-if="caseId && auth.has('evidence.create') && rec?.status === 'READY'">
          <template v-if="!evidenced">
            <el-button size="small" @click="doEvidence">🔒 存证本次录音</el-button>
            <span class="note" style="font-size:11px;color:var(--sec)">送易保全备案，可按情况决定是否存证</span>
          </template>
          <template v-else>
            <span class="tag suc" style="font-size:11px">✓ 已发起存证 · 备案中（约10分钟出证书）</span>
            <el-button size="small" @click="downloadEvidenceFile">⬇ 存证文件</el-button>
            <el-button size="small" @click="downloadCert">⬇ 存证证书</el-button>
          </template>
        </template>
        <span style="flex:1"></span>
        <el-button size="small" @click="close">关闭</el-button>
        <el-button size="small" type="primary" @click="saveMark">保存标注</el-button>
      </div>
    </div>
  </Teleport>
</template>
