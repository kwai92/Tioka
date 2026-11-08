<template>
  <div class="state-forbidden" role="status">
    <p>{{ message }}</p>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    message?: string
  }>(),
  { message: '你没有查看此内容的权限' }
)
</script>

<style scoped>
.state-forbidden {
  color: var(--muted);
}
</style>
