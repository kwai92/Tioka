<template>
  <div class="m3-placeholder">
    <h1>{{ title }}</h1>
    <p>M3 占位页（P3-00c）。后续 FE 包在本路径实现，勿改路由登记。</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '页面建设中')
</script>

<style scoped>
.m3-placeholder {
  max-width: 40rem;
}
.m3-placeholder h1 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
}
.m3-placeholder p {
  margin: 0;
  color: var(--muted);
}
</style>
