<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import ProjectEditDialog from '../components/ProjectEditDialog.vue'
import { statusLabel } from '../constants/enums'

// 真 GET /projects（契约客户端，类型来自 schema.d.ts）。资金双线：平台/物业见 commInRate，服务商视角字段级无。
const router = useRouter()
const auth = useAuth()
const { showCommInRate, ratePct } = useRoleFields()
const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  const { data, error } = await api.GET('/projects', { params: { query: { page: page.value, size: size.value } } })
  loading.value = false
  if (error) { ElMessage.error('加载失败'); return }
  items.value = data?.items ?? []
  total.value = data?.meta?.total ?? 0
}

// 新建项目入口(H-07·proj.edit 门控)。共用编辑对话框，project=null=新建。
const editDlg = ref(false)
function openCreate() { editDlg.value = true }
function onSaved() { load() }
onMounted(load)

// 纯展示辅助：项目状态 → ds-admin .tag 配色（启用=suc、停用=inf，其它兜底 inf）。不改数据/逻辑。
const statusTag = (s?: string) => (s === '启用' || s === 'ACTIVE' || s === 'ENABLED' ? 'suc' : s === '停用' || s === 'INACTIVE' || s === 'DISABLED' ? 'inf' : 'inf')
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>项目</div>
      <div class="ops">
        <span class="note" style="margin:0">GET /projects · 共 {{ total }} · 契约客户端 + 数据范围裁剪</span>
        <button v-if="auth.has('proj.edit')" class="btn" @click="openCreate">+ 新建项目</button>
      </div>
    </div>

    <table v-loading="loading">
      <thead>
        <tr>
          <th style="width:60px">ID</th>
          <th>项目</th>
          <th>物业</th>
          <th>区域</th>
          <!-- 资金双线：收佣比例整列仅平台/物业视角渲染，服务商视角整列(含列头)不出，不以占位串泄露字段存在性(H-03) -->
          <th v-if="showCommInRate">收佣比例</th>
          <th style="width:80px">状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in items" :key="row.id" class="row-click" @click="router.push(`/projects/${row.id}`)">
          <td>{{ row.id }}</td>
          <td>{{ row.name || '—' }}</td>
          <td>{{ row.org || '—' }}</td>
          <td>{{ row.area || '—' }}</td>
          <td v-if="showCommInRate" class="num">{{ ratePct(row.commInRate) }}</td>
          <td><span class="tag" :class="statusTag(row.status)" :title="row.status">{{ statusLabel(row.status) }}</span></td>
        </tr>
        <tr v-if="!loading && !items.length">
          <td :colspan="showCommInRate ? 6 : 5" style="text-align:center;color:var(--sec);padding:32px 0">暂无数据</td>
        </tr>
      </tbody>
    </table>

    <div class="page-bar" v-if="total > size">
      <span style="margin-right:8px">共 {{ total }} 条</span>
      <div class="pg" @click="page > 1 && (page--, load())">‹</div>
      <div class="pg on">{{ page }}</div>
      <div class="pg" @click="page * size < total && (page++, load())">›</div>
    </div>

    <ProjectEditDialog v-model="editDlg" :project="null" @saved="onSaved" />
  </div>
</template>
