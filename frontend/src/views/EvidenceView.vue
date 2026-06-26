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
// H-02: FAILED 失败重试(仅 FAILED→ISSUING·POST /evidence/{id}/retry，按次只向物业计费)；非 evidence.create 不显
async function doRetry(row: any) {
  const { error } = await api.POST('/evidence/{id}/retry', { params: { path: { id: row.id } } } as any)
  if (error) { ElMessage.error('重试失败：' + ((error as any)?.message ?? '')); return }
  ElMessage.success('已重新出证（ISSUING）'); load()
}
onMounted(load)
</script>

<template>
  <el-card header="存证（GET /evidence · 三方隔离 · 哈希链防篡改）">
    <!-- H-02: 只读入口提示 — 创建在案件作业台「发起存证」，本页仅查看/验真/下载证书 -->
    <el-alert :type="canCreate ? 'success' : 'info'" :closable="false" style="margin-bottom:10px"
      :title="canCreate ? '存证创建入口在案件作业台「发起存证」；本页用于查看、验真与下载证书。' : '只读视图：可查看、验真与下载证书；存证发起需在案件作业台由具备创建权限的角色操作。'" />
    <el-table v-loading="loading" :data="items" border size="small">
      <el-table-column prop="certNo" label="存证号" />
      <el-table-column prop="scene" label="场景" width="120" />
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag size="small" :type="row.status==='ISSUED'?'success':(row.status==='FAILED'?'danger':'warning')">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="issuedAt" label="出证时间" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" @click="doVerify(row)">验真</el-button>
          <el-button v-if="row.certUrl" size="small" text type="primary" tag="a" :href="row.certUrl" target="_blank">证书</el-button>
          <el-button v-if="row.status==='FAILED' && canCreate" size="small" type="warning" @click="doRetry(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="vdlg" title="存证验真（GET /evidence/{id}/verify · 公开校验）" width="440px">
      <el-result v-if="verify" :icon="verify.valid ? 'success' : 'error'" :title="verify.valid ? '验真通过 · 未被篡改' : '验真失败'">
        <template #sub-title>
          <div style="text-align:left">
            <p>存证号：{{ verify.certNo }}</p>
            <p>场景：{{ verify.scene }}</p>
            <p>出证时间：{{ verify.issuedAt }}</p>
            <p style="word-break:break-all">哈希：<code>{{ verify.hash }}</code></p>
          </div>
        </template>
      </el-result>
    </el-dialog>
  </el-card>
</template>
