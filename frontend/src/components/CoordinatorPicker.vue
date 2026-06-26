<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

/**
 * 协调员多选器(US-M2-02/BR-M2-13)：候选源 GET /members?role=PC(本组织 PC)，
 * el-transfer 全量选中后由父级提交 PUT /projects|batches/{id}/coordinators(全量覆盖)。
 * 供项目级与批次级复用。回填已关联 ids。
 */
const props = defineProps<{
  modelValue: boolean
  // 已关联协调员(CoordinatorRef[]：{id,name})，用于回填初值
  selected: { id?: string; name?: string }[]
  title?: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  // 提交全量 coordinatorIds（父级据此调对应 PUT 端点）
  (e: 'submit', coordinatorIds: string[]): void
}>()

// el-transfer 数据：候选 PC 列表 + 当前右侧选中 ids
const candidates = ref<{ key: string; label: string }[]>([])
const checked = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

async function loadCandidates() {
  loading.value = true
  const { data, error } = await api.GET('/members', { params: { query: { role: 'PC', page: 1, size: 200 } } })
  loading.value = false
  if (error) { ElMessage.error('加载协调员候选失败'); return }
  const items = (data?.items ?? [])
  candidates.value = items.map((m) => ({ key: String(m.id ?? ''), label: m.name || m.username || String(m.id ?? '') }))
}

// 弹窗打开时加载候选 + 回填已关联
watch(() => props.modelValue, (open) => {
  if (open) {
    checked.value = (props.selected ?? []).map((c) => String(c.id ?? '')).filter((x) => x)
    loadCandidates()
  }
})

function close() { emit('update:modelValue', false) }
function submit() {
  saving.value = true
  emit('submit', checked.value.slice())
  saving.value = false
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title || '维护协调员（PC 多对多·全量覆盖）'" width="560px"
    @update:model-value="(v:boolean)=>emit('update:modelValue', v)">
    <div v-loading="loading">
      <el-transfer v-model="checked" :data="candidates" :titles="['可选 PC', '已关联']" filterable />
      <div style="margin-top:8px;color:#909399;font-size:12px">候选来自本组织 PC（GET /members?role=PC）。提交为全量覆盖，清空即解除全部关联。</div>
    </div>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存协调员</el-button>
    </template>
  </el-dialog>
</template>
