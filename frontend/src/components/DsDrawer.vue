<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  title?: string
  width?: number
  minWidth?: number
  maxWidth?: number
  closeOnMask?: boolean
}>(), {
  title: '',
  width: 560,
  minWidth: 360,
  maxWidth: 1200,
  closeOnMask: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'closed': []
}>()

const drawerWidth = ref(props.width)
const isResizing = ref(false)

// 同步外部 width 变化
watch(() => props.width, (w) => { if (!isResizing.value) drawerWidth.value = w })

function close() {
  emit('update:modelValue', false)
}

function onMaskClick() {
  if (props.closeOnMask) close()
}

// ── resize ──
function startResize(e: PointerEvent) {
  isResizing.value = true
  const startX = e.clientX
  const startWidth = drawerWidth.value

  const maxW = Math.min(props.maxWidth, window.innerWidth * 0.92)

  function onMove(ev: PointerEvent) {
    const delta = startX - ev.clientX // 左拖→抽屉变宽
    const w = Math.min(maxW, Math.max(props.minWidth, startWidth + delta))
    drawerWidth.value = w
  }
  function onUp() {
    isResizing.value = false
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
  }
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}

// 关闭动画完成后 emit closed（与 CSS transition 0.28s 对齐）
let closeTimer: ReturnType<typeof setTimeout> | null = null
watch(() => props.modelValue, (val) => {
  if (!val) {
    closeTimer = setTimeout(() => emit('closed'), 300)
  }
})
onBeforeUnmount(() => { if (closeTimer) clearTimeout(closeTimer) })
</script>

<template>
  <Teleport to="body">
    <div class="mask" :class="{ on: modelValue }" @click.self="onMaskClick">
      <div
        class="drawer"
        :class="{ 'is-resizing': isResizing }"
        :style="{ width: drawerWidth + 'px' }"
        role="dialog"
        aria-modal="true"
        :aria-label="title || '抽屉'"
      >
        <!-- resize handle（左边缘） -->
        <div
          class="dr-resize"
          :class="{ dragging: isResizing }"
          @pointerdown.prevent="startResize"
          title="拖动调节宽度"
        ></div>

        <div class="dh">
          <span>{{ title }}</span>
          <span class="x" role="button" tabindex="0" aria-label="关闭" @click="close" @keydown.enter="close">×</span>
        </div>

        <div class="dbody">
          <slot />
        </div>

        <div v-if="$slots.footer" class="df-footer">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
