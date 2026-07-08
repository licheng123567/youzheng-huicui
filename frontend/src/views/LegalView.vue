<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import DsDrawer from '../components/DsDrawer.vue'
import { downloadAuthedFile } from '../utils/download'

// 送达管理（协调员）：送达记录列表。由已上传的送达凭证附件(case_attachment.delivery_type 非空)聚合而成，
// 来源两类——app 扫码/手机上传 与 PC 后台直传；是否存证/存证态由关联 DELIVERY 存证派生。
// 「法务案件列表」概念已移除（法务只是催收跟进手段之一），本页不再客户端筛 legalStage。
const items = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

// 送达类型 → 中文 + 配色
const TYPE_NAME: Record<string, string> = {
  LAWYER_LETTER: '律师函', COLLECTION_NOTICE: '催收单', COURT_DOC: '诉讼文书', OTHER: '其他',
}
const typeName = (t?: string) => TYPE_NAME[t ?? ''] ?? t ?? '—'
const TYPE_TAG: Record<string, string> = {
  LAWYER_LETTER: 'war', COLLECTION_NOTICE: 'inf', COURT_DOC: 'dan', OTHER: 'inf',
}
const typeTag = (t?: string) => TYPE_TAG[t ?? ''] ?? 'inf'

// 送达渠道 → 中文
const channelName = (c?: string) => (c === 'APP' ? 'app扫码' : c === 'BACKEND' ? 'PC后台' : '—')

// 存证态 → 中文 + 配色（未存证=无关联存证）
function evidenceLabel(row: any): string {
  if (!row.evidenced) return '未存证'
  return ({ ISSUING: '存证中', ISSUED: '已存证', FAILED: '存证失败' } as any)[row.evidenceStatus] ?? '存证中'
}
function evidenceTag(row: any): string {
  if (!row.evidenced) return 'inf'
  return ({ ISSUING: 'war', ISSUED: 'suc', FAILED: 'dan' } as any)[row.evidenceStatus] ?? 'war'
}
const fmtTs = (t?: string) => (t ? String(t).slice(0, 16).replace('T', ' ') : '—')

const page = ref(1)
const size = ref(20)

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/deliveries', { params: { query: { page: page.value, size: size.value } } as any })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = (data as any)?.items ?? []
  total.value = (data as any)?.meta?.total ?? 0
}

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const pages = computed(() => {
  const n = pageCount.value, cur = page.value
  let start = Math.max(1, cur - 2), end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})
function onPage(p: number) {
  if (p < 1 || p > pageCount.value || p === page.value) return
  page.value = p
  load()
}

// ── 送达单明细抽屉 ──
const dlg = ref(false)
const sel = ref<any>({})
function openDetail(row: any) { sel.value = row; dlg.value = true }
// 文件下载（鉴权，与别处一致）
function downloadFile(row: any) {
  if (!row.id) return
  const name = row.filename || ('送达凭证_' + row.id)
  downloadAuthedFile('/v1/attachments/' + row.id, name, '该送达凭证文件不存在。')
}
// 证书下载（仅已存证 ISSUED 可下；未就绪 409 优雅提示）
function downloadCert(row: any) {
  if (!row.evidenceId) return
  downloadAuthedFile('/v1/evidence/' + row.evidenceId + '/certificate', 'evidence-cert-' + row.evidenceId + '.zip', '备案证书未就绪（约10分钟出证），请稍后再下。')
}

onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>送达管理</div>
      <div class="ops"><span class="note" style="margin:0">GET /deliveries · 送达凭证聚合</span></div>
    </div>

    <div class="note">协调员在案件「送达存证 · 上传文件/凭证」上传的送达件（app 扫码 / PC 后台）汇总于此；是否存证由是否上链决定。</div>

    <table v-loading="loading" style="margin-top:12px">
      <thead>
        <tr>
          <th style="width:80px">房号</th>
          <th>项目</th>
          <th style="width:110px">批次</th>
          <th style="width:130px">送达时间</th>
          <th style="width:90px">送达类型</th>
          <th style="width:80px">送达渠道</th>
          <th style="width:90px">是否存证</th>
          <th style="width:80px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.room || '—' }}</td>
          <td>{{ row.projectName || '—' }}</td>
          <td>{{ row.batchNo || '—' }}</td>
          <td>{{ fmtTs(row.deliveredAt) }}</td>
          <td><span class="tag" :class="typeTag(row.deliveryType)">{{ typeName(row.deliveryType) }}</span></td>
          <td>{{ channelName(row.channel) }}</td>
          <td><span class="tag" :class="evidenceTag(row)">{{ evidenceLabel(row) }}</span></td>
          <td><button class="btn df" @click="openDetail(row)">明细</button></td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="8" style="text-align:center;color:var(--sec);padding:32px 0">暂无送达记录</td>
        </tr>
      </tbody>
    </table>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="onPage(page - 1)">‹</div>
      <div v-for="p in pages" :key="p" class="pg" :class="{ on: p === page }" @click="onPage(p)">{{ p }}</div>
      <div class="pg" @click="onPage(page + 1)">›</div>
    </div>

    <!-- 送达单明细抽屉：送达要素 + 文件下载 + 存证情况（已存证可下证书） -->
    <DsDrawer v-model="dlg" title="送达单明细">
      <div class="desc" style="padding:4px 2px">
        <div class="r"><div class="k">房号</div><div class="v">{{ sel.room || '—' }}</div></div>
        <div class="r"><div class="k">项目</div><div class="v">{{ sel.projectName || '—' }}</div></div>
        <div class="r"><div class="k">批次</div><div class="v">{{ sel.batchNo || '—' }}</div></div>
        <div class="r"><div class="k">送达时间</div><div class="v">{{ fmtTs(sel.deliveredAt) }}</div></div>
        <div class="r"><div class="k">送达类型</div><div class="v"><span class="tag" :class="typeTag(sel.deliveryType)">{{ typeName(sel.deliveryType) }}</span></div></div>
        <div class="r"><div class="k">送达渠道</div><div class="v">{{ channelName(sel.channel) }}</div></div>

        <div class="lbl" style="margin-top:14px">送达文件</div>
        <div style="display:flex;align-items:center;gap:8px;margin-top:6px">
          <span style="font-size:13px">{{ sel.filename || ('送达凭证_' + sel.id) }}</span>
          <button class="btn df sm" @click="downloadFile(sel)">⬇ 下载</button>
        </div>

        <div class="lbl" style="margin-top:14px">存证情况</div>
        <div style="display:flex;align-items:center;gap:8px;margin-top:6px">
          <span class="tag" :class="evidenceTag(sel)">{{ evidenceLabel(sel) }}</span>
          <button v-if="sel.evidenced && sel.evidenceStatus === 'ISSUED'" class="btn df sm" @click="downloadCert(sel)">⬇ 下载证书</button>
        </div>
        <div v-if="!sel.evidenced" class="note" style="margin-top:6px;font-size:12px">该送达件未上链存证；如需固证，请在案件「上传文件/凭证」勾选“同时上链存证”。</div>
      </div>
    </DsDrawer>
  </div>
</template>
