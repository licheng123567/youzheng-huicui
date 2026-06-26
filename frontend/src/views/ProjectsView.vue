<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import { useRoleFields } from '../composables/useRoleFields'
import ProjectEditDialog from '../components/ProjectEditDialog.vue'

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
</script>

<template>
  <el-card header="项目（GET /projects · 契约客户端 + 数据范围裁剪）">
    <el-button v-if="auth.has('proj.edit')" type="primary" size="small" style="margin-bottom:10px" @click="openCreate">新建项目</el-button>
    <el-table v-loading="loading" :data="items" border @row-click="(r:any)=>router.push(`/projects/${r.id}`)" style="cursor:pointer">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="项目" />
      <el-table-column prop="org" label="物业" />
      <el-table-column prop="area" label="区域" width="100" />
      <!-- 资金双线：收佣比例整列仅平台/物业视角渲染，服务商视角整列(含列头)不出，不以占位串泄露字段存在性(H-03) -->
      <el-table-column v-if="showCommInRate" label="收佣比例" width="110">
        <template #default="{ row }">{{ ratePct(row.commInRate) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
    </el-table>
    <el-pagination style="margin-top:12px" layout="total, prev, pager, next" :total="total"
      :page-size="size" :current-page="page" @current-change="(p:number)=>{page=p;load()}" />

    <ProjectEditDialog v-model="editDlg" :project="null" @saved="onSaved" />
  </el-card>
</template>
