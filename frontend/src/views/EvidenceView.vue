<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'

// M6 存证：GET /evidence(三方隔离·物业可见/服务商空) + 验真(GET /evidence/{id}/verify·public 防篡改校验)。
// H-02: 本页为只读列表/验真/证书下载入口；存证「创建」入口分离在案件作业台(发起存证)，仅 evidence.create 可见。
const auth = useAuth()
const canCreate = computed<boolean>(function () { return auth.has('evidence.create') })
const items = ref<any[]>([])
const loading = ref(false)
const verify = ref<any>(null)
const vdlg = ref(false)

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/evidence', { params: { query: { page: 1, size: 30 } } as any })
  loading.value = false
  if (error) { ElMessage.error('存证加载失败'); return }
  items.value = (data as any)?.items ?? []
}
// 验真（public 端点·任何人凭存证号可验）
async function doVerify(row: any) {
  const { data, error } = await api.GET('/evidence/{id}/verify', { params: { path: { id: row.id } } })
  if (error) { ElMessage.error('验真失败'); return }
  verify.value = data; vdlg.value = true
}
// 纯展示：存证状态 → ds-admin .tag 配色（ISSUED 成功 / FAILED 危险 / 其余处理中）
const statusTag = (s?: string) => (s === 'ISSUED' ? 'suc' : (s === 'FAILED' ? 'dan' : 'war'))
// H-02: FAILED 失败重试(仅 FAILED→ISSUING·POST /evidence/{id}/retry，按次只向物业计费)；非 evidence.create 不显
async function doRetry(row: any) {
  const { error } = await api.POST('/evidence/{id}/retry', { params: { path: { id: row.id } } } as any)
  if (error) { ElMessage.error('重试失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已重新出证（ISSUING）'); load()
}
onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>存证管理</div>
      <div class="ops"><span class="note" style="margin:0">GET /evidence · 三方隔离 · 哈希链防篡改</span></div>
    </div>

    <!-- H-02: 只读入口提示 — 创建在案件作业台「发起存证」，本页仅查看/验真/下载证书 -->
    <div class="alert" :class="canCreate ? 'ok' : 'info'" style="margin-top:0;margin-bottom:14px">
      <span>{{ canCreate ? '存证创建入口在案件作业台「发起存证」；本页用于查看、验真与下载证书。' : '只读视图：可查看、验真与下载证书；存证发起需在案件作业台由具备创建权限的角色操作。' }}</span>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th>存证号</th>
          <th style="width:120px">场景</th>
          <th style="width:100px">状态</th>
          <th style="width:180px">出证时间</th>
          <th style="width:200px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id">
          <td>{{ row.certNo || '—' }}</td>
          <td>{{ row.scene || '—' }}</td>
          <td><span class="tag" :class="statusTag(row.status)">{{ row.status }}</span></td>
          <td>{{ row.issuedAt || '—' }}</td>
          <td>
            <a class="btn txt" @click="doVerify(row)">验真</a>
            <a v-if="row.certUrl" class="btn txt" :href="row.certUrl" target="_blank">证书</a>
            <a v-if="row.status==='FAILED' && canCreate" class="btn txt wn" @click="doRetry(row)">重试</a>
          </td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td colspan="5" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="note">存证只向物业按次计费、三场景同价；每次有可下载证书 + 第三方/法院核验。存证失败不计费，可点「重试」重新发起。</div>

    <!-- 验真弹窗：保留 el-dialog 外壳（公开校验 GET /evidence/{id}/verify） -->
    <el-dialog v-model="vdlg" title="存证验真（GET /evidence/{id}/verify · 公开校验）" width="440px">
      <template v-if="verify">
        <div class="alert" :class="verify.valid ? 'ok' : 'err'" style="margin-top:0;margin-bottom:14px">
          <span>{{ verify.valid ? '验真通过 · 未被篡改' : '验真失败' }}</span>
        </div>
        <div class="desc">
          <div class="r"><div class="k">存证号</div><div class="v">{{ verify.certNo }}</div></div>
          <div class="r"><div class="k">场景</div><div class="v">{{ verify.scene }}</div></div>
          <div class="r"><div class="k">出证时间</div><div class="v">{{ verify.issuedAt }}</div></div>
          <div class="r"><div class="k">哈希</div><div class="v" style="word-break:break-all"><code>{{ verify.hash }}</code></div></div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
