<script setup lang="ts">
import { computed } from 'vue'

/**
 * 分页条（ds-admin 的 .page-bar 范式，从 CasesView 抽出来复用）。
 *
 * **为什么这是个正经问题而不是"体验优化"**：13 个主列表页此前都硬编码 `page: 1` 且没有任何分页控件 ——
 * 后端按默认页长返回，前端只画第一页，**第 21 个批次、第 101 个案件既看不见、也无从得知它存在**。
 * 这不是"翻不了页"，是**静默截断**：用户以为自己看到了全部。
 * 更糟的是有几个页面还在这个被截断的一页上 reduce 出 KPI —— 那个数字是错的，而且看不出错。
 *
 * 只在 `total > size` 时渲染：数据装得下一页时不占地方。
 */
const props = defineProps<{
  page: number
  size: number
  total: number
}>()

const emit = defineEmits<{ (e: 'update:page', p: number): void }>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

/** 当前页附近最多 5 个页码（页数很多时不把工具栏撑爆）。 */
const pages = computed(() => {
  const n = pageCount.value
  const cur = props.page
  let start = Math.max(1, cur - 2)
  const end = Math.min(n, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
})

function go(p: number) {
  if (p < 1 || p > pageCount.value || p === props.page) return
  emit('update:page', p)
}
</script>

<template>
  <div class="page-bar" v-if="total > size">
    <span style="margin-right:8px">共 {{ total }} 条 · 第 {{ page }}/{{ pageCount }} 页</span>
    <div class="pg" @click="go(page - 1)">‹</div>
    <div v-for="p in pages" :key="p" class="pg" :class="{ on: p === page }" @click="go(p)">{{ p }}</div>
    <div class="pg" @click="go(page + 1)">›</div>
  </div>
</template>
