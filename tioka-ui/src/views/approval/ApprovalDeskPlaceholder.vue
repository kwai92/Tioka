<template>
  <div class="page placeholder">
    <h1>审批台</h1>
    <p class="muted">HCI-05 占位（PK-00d）。三类主体按注册表渲染；实现见 PK-F2。</p>
    <ul class="subjects">
      <li v-for="t in subjectTypes" :key="t">
        <component :is="resolveApprovalSubjectRenderer(t)" :subject-type="t" />
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import {
  APPROVAL_SUBJECT_TYPES,
  resolveApprovalSubjectRenderer
} from './subjectRegistry'

const subjectTypes = APPROVAL_SUBJECT_TYPES
</script>

<style scoped>
.placeholder { display: grid; gap: 12px; max-width: 720px; }
.muted { color: var(--muted, #8aa0b5); margin: 0; }
.subjects { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
</style>
