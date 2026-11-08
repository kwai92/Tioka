<template>
  <div class="state-loading" role="status" aria-live="polite">
    <div class="skeleton" aria-hidden="true" />
    <p v-if="slow">仍在加载… <button type="button" @click="emit('retry')">重试</button></p>
    <p v-else>{{ message }}</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    message?: string
    /** 超过该毫秒显示「仍在加载」与重试（FE-00 §5） */
    slowAfterMs?: number
  }>(),
  { message: '加载中…', slowAfterMs: 10_000 }
)

const emit = defineEmits<{ retry: [] }>()
const slow = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

onMounted(() => {
  timer = setTimeout(() => {
    slow.value = true
  }, props.slowAfterMs)
})
onUnmounted(() => {
  if (timer) clearTimeout(timer)
})
</script>

<style scoped>
.state-loading {
  display: grid;
  gap: 12px;
  color: var(--muted);
}
.skeleton {
  height: 120px;
  border-radius: 8px;
  background: linear-gradient(90deg, #1e293b 25%, #334155 50%, #1e293b 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}
@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
button {
  margin-left: 8px;
  cursor: pointer;
}
</style>
