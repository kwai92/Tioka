<template>
  <div class="state-error" role="alert">
    <p class="msg">{{ message }}</p>
    <p v-if="code" class="code">错误码：{{ code }}</p>
    <div class="actions">
      <button type="button" @click="emit('retry')">重试</button>
      <button v-if="requestId" type="button" @click="copyRequestId">复制请求号</button>
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  message: string
  code?: string
  /** X-Tioka-Request-Id */
  requestId?: string
}>()

const emit = defineEmits<{ retry: [] }>()

async function copyRequestId() {
  if (!props.requestId) return
  try {
    await navigator.clipboard.writeText(props.requestId)
  } catch {
    /* ignore */
  }
}
</script>

<style scoped>
.state-error {
  display: grid;
  gap: 8px;
}
.msg {
  margin: 0;
}
.code {
  margin: 0;
  color: var(--muted);
  font-size: 0.85rem;
}
.actions {
  display: flex;
  gap: 8px;
}
button {
  cursor: pointer;
  border: 1px solid #334155;
  background: transparent;
  color: var(--text);
  border-radius: 8px;
  padding: 6px 12px;
}
</style>
