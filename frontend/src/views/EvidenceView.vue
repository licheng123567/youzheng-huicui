<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

// M6 存证：GET /evidence(三方隔离·物业可见/服务商空) + 验真(GET /evidence/{id}/verify·public 防篡改校验)。
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
onMounted(load)
</script>

<template>
  <el-card header="存证（GET /evidence · 三方隔离 · 哈希链防篡改）">
    <el-table v-loading="loading" :data="items" border size="small">
      <el-table-column prop="certNo" label="存证号" />
      <el-table-column prop="scene" label="场景" width="120" />
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag size="small" :type="row.status==='ISSUED'?'success':'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="issuedAt" label="出证时间" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="doVerify(row)">验真</el-button>
          <el-button v-if="row.certUrl" size="small" text type="primary" tag="a" :href="row.certUrl" target="_blank">证书</el-button>
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
