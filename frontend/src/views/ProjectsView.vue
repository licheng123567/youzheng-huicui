<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

// 数据范围演示：调后端 /v1/projects-scope-demo（横切层 demo 端点，非契约完整 GET /projects）。
// 登录 admin(平台)→见全部 3；登录 cuihu_pl(翠湖)→仅见翠湖 2——服务端 x-data-scope 强制裁剪。
// 真 GET /projects（Project/ProjectForProvider 按角色 + 分页）在 M2 用契约客户端 api.GET('/projects')。
interface ProjectLite { id: string; name: string; orgName: string; status: string }
const items = ref<ProjectLite[]>([])
const scope = ref('')
const loading = ref(true)

onMounted(async () => {
  try {
    const res = await fetch('/v1/projects-scope-demo', {
      headers: { Authorization: `Bearer ${localStorage.getItem('token')}` },
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    items.value = data.items
    scope.value = data.scopeApplied
  } catch (e) {
    ElMessage.error('加载项目失败：' + (e as Error).message)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-card header="项目列表 · 数据范围隔离演示">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      :title="`服务端按当前主体裁剪：${scope}。换 cuihu_pl 登录会看不到阳光物业的项目。`" />
    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="项目" />
      <el-table-column prop="orgName" label="物业" />
      <el-table-column prop="status" label="状态" width="100" />
    </el-table>
  </el-card>
</template>
