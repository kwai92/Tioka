<template>
  <div class="page">
    <h1>观测</h1>
    <p class="muted">资产目录 + 环境 + 审计最近记录；链路追踪改链 Jaeger（GOV-08）</p>
    <div class="row">
      <input v-model="q" placeholder="搜索资产" />
      <button @click="search">搜索</button>
      <button class="ghost" @click="loadMeta">刷新元数据</button>
      <a
        v-if="jaegerUi"
        class="jaeger"
        :href="jaegerUi"
        target="_blank"
        rel="noopener noreferrer"
      >打开 Jaeger</a>
    </div>
    <section class="card">
      <h2>环境</h2>
      <pre>{{ env }}</pre>
      <p v-if="traceUrlTemplate" class="muted">
        Trace URL 模板：<code>{{ traceUrlTemplate }}</code>
      </p>
    </section>
    <section class="card">
      <h2>目录</h2>
      <ul>
        <li v-for="(item, i) in catalog" :key="i">{{ item.type }} · {{ item.name }} · {{ item.id }}</li>
      </ul>
    </section>
    <section class="card">
      <h2>审计</h2>
      <pre>{{ audit }}</pre>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const q = ref('agent')
const catalog = ref([])
const env = ref({})
const audit = ref([])

const traceUrlTemplate = computed(() => env.value?.traceUrlTemplate || '')
const jaegerUi = computed(() => {
  if (env.value?.jaegerUi) return env.value.jaegerUi
  const t = traceUrlTemplate.value
  if (!t) return ''
  return t.replace('/trace/{traceId}', '').replace('{traceId}', '') || 'http://localhost:16686'
})

async function search() {
  catalog.value = await api(`/api/catalog?q=${encodeURIComponent(q.value)}`)
}

async function loadMeta() {
  env.value = await api('/api/ops/env')
  audit.value = await api('/api/ops/audit/recent?limit=10')
}

onMounted(async () => {
  await search()
  await loadMeta()
})
</script>

<style scoped>
.page{max-width:900px;display:grid;gap:12px}
.muted{color:var(--muted)}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.card{border:1px solid #243041;border-radius:12px;padding:14px}
input,button{border-radius:10px;border:1px solid #334155;background:#0b1220;color:var(--text);padding:10px 12px}
button{background:var(--accent);border:0;font-weight:600;cursor:pointer}
button.ghost{background:transparent;border:1px solid #334155;color:var(--muted)}
a.jaeger{color:var(--accent);font-weight:600;text-decoration:none}
pre{background:#0b1220;padding:8px;border-radius:8px;overflow:auto}
ul{list-style:none;padding:0}
code{font-size:12px}
</style>
