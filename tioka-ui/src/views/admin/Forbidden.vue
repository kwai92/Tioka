<template>
  <div class="page">
    <h1>403</h1>
    <p class="muted">当前账号没有访问该管理页面的权限。</p>
    <router-link to="/chat">返回工作台</router-link>
  </div>
</template>

<style scoped>
.page { padding: 8px; }
.muted { color: var(--muted); }
a { color: var(--accent); }
</style>
