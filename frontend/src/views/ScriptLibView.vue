<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import Pager from '../components/Pager.vue'
import type { components } from '../api/schema'
import DsDrawer from '../components/DsDrawer.vue'

// 话术库（平台·飞轮护城河 BR-M5-06/06a）。独立页，挂 /script-lib。
// 列表 GET /script-lib；新建 POST /script-lib(ScriptInput)；变体晋升 POST /script-lib/{id}/variant/promote。
type ScriptStatus = components['schemas']['ScriptStatusEnum']
type ScriptInput = components['schemas']['ScriptInput']

const items = ref<any[]>([])
// 分页：此前硬编码 page:1 且无分页控件——超出一页的数据静默消失。
const page = ref(1)
const size = ref(50)
const total = ref(0)
function onPage(p: number) { page.value = p; load() }
const loading = ref(false)
const filter = ref({ scene: '', source: '', status: '' })
const pctRate = (r?: number) => (r == null ? '—' : (r * 100).toFixed(1) + '%')

// 状态枚举 3 值 → 中文标签 + ds-admin .tag 配色（suc/war/inf）
const STATUS_LABEL: Record<string, string> = {
  EFFECTIVE: '现行', CANDIDATE: '候选', RETIRED: '已退役',
}
const statusLabel = (s?: string) => STATUS_LABEL[s ?? ''] ?? s ?? '—'
const statusTag = (s?: string) =>
  s === 'EFFECTIVE' ? 'suc' : s === 'RETIRED' ? 'inf' : 'war'

// 来源枚举 → 中文（AI 挖掘 / 专家）
const sourceLabel = (s?: string) =>
  s === 'AI_MINED' ? 'AI挖掘' : s === 'EXPERT' ? '专家' : s ?? '—'

// 文本截断（话术正文在 variant.text；无变体则 —）
const truncate = (t?: string, n = 40) =>
  !t ? '—' : t.length > n ? t.slice(0, n) + '…' : t

// 列表请求：不带筛选，仅拉首屏（不分页，前端简列表）。失败提示不阻断。
async function load() {
  loading.value = true
  const { data, error } = await api.GET('/script-lib', {
    params: { query: { scene: filter.value.scene || undefined, source: filter.value.source || undefined, status: filter.value.status || undefined, page: page.value, size: size.value } } as any,
  })
  loading.value = false
  if (error) { ElMessage.error('加载话术库失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}
function resetFilter() { filter.value = { scene: '', source: '', status: '' }; load() }
// 场景选项从当前数据去重
const scenes = computed(() => [...new Set(items.value.map((r) => r.scene).filter(Boolean))])

// 查看详情(含单条话术漏斗 GET /script-lib/flywheel?scriptId)
const vDlg = ref(false); const vRow = ref<any>(null); const vFunnel = ref<any[]>([])
async function viewScript(row: any) {
  vRow.value = row; vFunnel.value = []; vDlg.value = true
  const { data } = await api.GET('/script-lib/flywheel', { params: { query: { scriptId: String(row.id) } } as any })
  vFunnel.value = (data as any)?.funnel ?? []
}

// 话术有效性飞轮（GET /script-lib/flywheel）：转化漏斗 + Wilson 下界趋势
const flywheel = ref<any>({ funnel: [], wilsonTrend: [], note: '' })
async function loadFlywheel() {
  const { data } = await api.GET('/script-lib/flywheel', {} as any)
  flywheel.value = data ?? { funnel: [], wilsonTrend: [], note: '' }
}
// 飞轮结算:按真实通话结果(承诺兑现/回款)回流重算话术 wilson/转化率(BR-M5-12 环6)。
const recomputing = ref(false)
async function recompute() {
  recomputing.value = true
  const { data, error } = await api.POST('/script-lib/recompute', {} as any)
  recomputing.value = false
  if (error) { ElMessage.error('结算失败：' + ((error as any)?.message ?? '')); return }
  const r = data as any
  ElMessage.success(`飞轮结算完成：回流重算 ${r?.recomputed ?? 0} 条${r?.promoted ? '、自动晋升 ' + r.promoted + ' 条' : ''}`)
  load(); loadFlywheel()
}

// 新建话术对话框。数组/对象字段初始即初始化，防白屏。
const dlg = ref(false)
const form = reactive<ScriptInput>({ scene: '', intent: '', cohort: '', text: '' })
function openDlg() {
  form.scene = ''; form.intent = ''; form.cohort = ''; form.text = ''
  dlg.value = true
}
async function createScript() {
  if (!form.scene || !form.text) { ElMessage.warning('场景与话术文本必填'); return }
  const { error } = await api.POST('/script-lib', { body: { ...form } as any })
  if (error) { ElMessage.error('新建话术失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已新建话术（候选）')
  dlg.value = false
  load()
}

// 变体晋升：现行 ← 优化变体（达标自动·专家人工复核 BR-M5-12a）。
async function promote(row: any) {
  const { error } = await api.POST('/script-lib/{id}/variant/promote', {
    params: { path: { id: String(row.id) } },
  } as any)
  if (error) { ElMessage.error('晋升失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('变体已晋升为现行')
  load()
}

onMounted(() => { load(); loadFlywheel() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>话术库</div>
      <div class="ops">
        <span class="note" style="margin:0">GET /script-lib · 飞轮护城河</span>
        <button class="btn df sm" :disabled="recomputing" @click="recompute">{{ recomputing ? '结算中…' : '飞轮结算' }}</button>
        <button class="btn" @click="openDlg">新建话术</button>
      </div>
    </div>

    <!-- 筛选：场景/来源/状态（后端 listScripts 已支持 query 参数） -->
    <div class="toolbar" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <select class="inp" v-model="filter.scene" style="min-width:150px" @change="load"><option value="">场景：全部</option><option v-for="sc in scenes" :key="sc" :value="sc">{{ sc }}</option></select>
      <select class="inp" v-model="filter.source" style="min-width:130px" @change="load"><option value="">来源：全部</option><option value="AI_MINED">AI挖掘</option><option value="EXPERT">专家</option></select>
      <select class="inp" v-model="filter.status" style="min-width:130px" @change="load"><option value="">状态：全部</option><option value="EFFECTIVE">现行</option><option value="CANDIDATE">候选</option><option value="RETIRED">已退役</option></select>
      <button class="btn df sm" @click="resetFilter">重置</button>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>场景</th>
          <th>意图</th>
          <th>人群</th>
          <th style="width:70px">来源</th>
          <th style="width:70px" title="使用次数">用量</th>
          <th style="width:80px">承诺转化</th>
          <th style="width:80px">回款转化</th>
          <th style="width:80px" title="Wilson 置信下界">Wilson</th>
          <th style="width:90px">状态</th>
          <th style="width:90px">AI 变体</th>
          <th style="width:150px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.scene || '—' }}</td>
          <td>{{ row.intent || '—' }}</td>
          <td>{{ row.cohort || '—' }}</td>
          <td>{{ sourceLabel(row.source) }}</td>
          <td class="num">{{ row.uses ?? 0 }}</td>
          <td class="num">{{ pctRate(row.promiseRate) }}</td>
          <td class="num">{{ pctRate(row.repayRate) }}</td>
          <td class="num">{{ row.wilson != null ? row.wilson.toFixed(3) : '—' }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ statusLabel(row.status) }}</span></td>
          <td>
            <span v-if="row.variant" class="tag war">{{ row.variant.uplift != null ? '+' + (row.variant.uplift * 100).toFixed(0) + '%' : '有变体' }}</span>
            <span v-else class="note" style="margin:0">—</span>
          </td>
          <td>
            <button class="btn txt" @click="viewScript(row)">查看</button>
            <button v-if="row.variant" class="btn txt" @click="promote(row)">晋升</button>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="11" style="text-align:center;color:var(--sec);padding:32px 0">暂无话术</td>
        </tr>
      </tbody>
    </table>

    <Pager :page="page" :size="size" :total="total" @update:page="onPage" />

    <!-- 话术有效性飞轮（承诺·回款转化信号 BR-M5-12） -->
    <div class="sec-title" style="margin-top:18px">话术有效性飞轮（承诺·回款转化信号）</div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:24px;flex-wrap:wrap">
      <div>
        <div class="sec-title" style="font-size:12px;margin-bottom:8px">转化漏斗</div>
        <div v-for="f in flywheel.funnel" :key="f.stage" style="display:flex;align-items:center;gap:8px;margin:6px 0">
          <span style="width:100px;font-size:12px">{{ f.stage }}</span>
          <div style="flex:1;height:14px;background:#e4e7ed;border-radius:7px;overflow:hidden">
            <div :style="{ width: Math.min(f.pct, 100) + '%', height: '100%', background: 'var(--primary,#2563EB)', borderRadius: '7px' }"></div>
          </div>
          <span style="width:78px;font-size:12px;text-align:right">{{ f.n }} / {{ f.pct }}%</span>
        </div>
        <div v-if="!flywheel.funnel.length" class="note">暂无转化数据</div>
      </div>
      <div>
        <div class="sec-title" style="font-size:12px;margin-bottom:8px">Wilson 下界趋势（月度承诺兑现率置信下界）</div>
        <div v-for="t in flywheel.wilsonTrend" :key="t.m" style="display:flex;align-items:center;gap:8px;margin:6px 0">
          <span style="width:40px;font-size:12px">{{ t.m }}</span>
          <div style="flex:1;height:14px;background:#e4e7ed;border-radius:7px;overflow:hidden">
            <div :style="{ width: (t.w * 100) + '%', height: '100%', background: '#15A35B', borderRadius: '7px' }"></div>
          </div>
          <span style="width:48px;font-size:12px;text-align:right">{{ t.w }}</span>
        </div>
        <div v-if="!flywheel.wilsonTrend.length" class="note">暂无月度趋势</div>
      </div>
    </div>
    <div class="note" style="margin-top:8px">{{ flywheel.note }}</div>
    <div class="alert info" style="margin-top:10px">话术库样本经脱敏合规处理：训练与样本已剥离业主 PII，对外仅输出 AI 策略与话术建议。</div>

    <DsDrawer v-model="vDlg" :title="`话术详情 · ${vRow?.scene ?? ''}`" :width="560">
      <template v-if="vRow">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="场景">{{ vRow.scene || '—' }}</el-descriptions-item>
          <el-descriptions-item label="意图">{{ vRow.intent || '—' }}</el-descriptions-item>
          <el-descriptions-item label="人群">{{ vRow.cohort || '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ sourceLabel(vRow.source) }}</el-descriptions-item>
          <el-descriptions-item label="用量">{{ vRow.uses ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(vRow.status) }}</el-descriptions-item>
          <el-descriptions-item label="承诺转化">{{ pctRate(vRow.promiseRate) }}</el-descriptions-item>
          <el-descriptions-item label="回款转化">{{ pctRate(vRow.repayRate) }}</el-descriptions-item>
          <el-descriptions-item label="Wilson">{{ vRow.wilson != null ? vRow.wilson.toFixed(3) : '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="sec-title" style="margin-top:12px">本话术转化漏斗</div>
        <div v-for="f in vFunnel" :key="f.stage" style="display:flex;align-items:center;gap:8px;margin:4px 0">
          <span style="width:80px;font-size:12px">{{ f.stage }}</span>
          <div style="flex:1;height:12px;background:#e4e7ed;border-radius:6px;overflow:hidden">
            <div :style="{ width: Math.min(f.pct,100) + '%', height:'100%', background:'var(--primary,#2563EB)', borderRadius:'6px' }"></div>
          </div>
          <span style="width:56px;font-size:12px;text-align:right">{{ f.n }} / {{ f.pct }}%</span>
        </div>
        <div v-if="!vFunnel.length" class="note">该话术暂无归因承诺（未被采纳或结果未回流）</div>
        <div class="sec-title" style="margin-top:12px">现行话术</div>
        <div style="white-space:pre-wrap;padding:8px;background:#f6f8fc;border-radius:6px">{{ vRow.variant?.text || '（正文在变体中·此条为 AI 挖掘种子）' }}</div>
        <template v-if="vRow.variant">
          <div class="sec-title" style="margin-top:12px">AI 优化变体 {{ vRow.variant.uplift != null ? '· 提升 ' + (vRow.variant.uplift * 100).toFixed(0) + '%' : '' }}</div>
          <div style="white-space:pre-wrap;padding:8px;background:#fff8e6;border-radius:6px">{{ vRow.variant.text }}</div>
        </template>
      </template>
      <template #footer><el-button @click="vDlg=false">关闭</el-button><el-button v-if="vRow?.variant" type="primary" @click="promote(vRow); vDlg=false">晋升变体</el-button></template>
    </DsDrawer>

    <DsDrawer v-model="dlg" title="新建话术">
      <el-form label-width="80px">
        <el-form-item label="场景" required>
          <el-input v-model="form.scene" placeholder="如：首次外呼/承诺爽约跟进" />
        </el-form-item>
        <el-form-item label="意图">
          <el-input v-model="form.intent" placeholder="如：促成承诺/化解异议" />
        </el-form-item>
        <el-form-item label="人群">
          <el-input v-model="form.cohort" placeholder="如：长期欠费/首逾" />
        </el-form-item>
        <el-form-item label="话术" required>
          <el-input v-model="form.text" type="textarea" :rows="4" placeholder="话术正文" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="createScript">新建</el-button>
      </template>
    </DsDrawer>
  </div>
</template>
