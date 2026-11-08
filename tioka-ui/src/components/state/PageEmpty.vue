<template>
  <div class="state-empty">
    <p class="msg">{{ message }}</p>
    <button v-if="actionLabel" type="button" class="primary" @click="emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    message?: string
    actionLabel?: string
  }>(),
  { message: '暂无数据' }
)

const emit = defineEmits<{ action: [] }>()
</script>

<style scoped>
.state-empty {
  display: grid;
  gap: 12px;
  justify-items: start;
  color: var(--muted);
}
.primary {
  border: none;
  background: #243447;
  color: var(--text);
  border-radius: 8px;
  padding: 8px 14px;
  cursor: pointer;
}
</style>
