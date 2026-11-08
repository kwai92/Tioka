<template>
  <div
    ref="root"
    class="virtual-list"
    data-testid="virtual-list"
    :style="{ height: `${height}px` }"
    @scroll="onScroll"
  >
    <div class="virtual-list__spacer" :style="{ height: `${totalHeight}px` }">
      <div class="virtual-list__window" :style="{ transform: `translateY(${offsetY}px)` }">
        <div
          v-for="entry in visible"
          :key="entry.key"
          class="virtual-list__row"
          data-testid="timeline-row"
          :style="{ height: `${itemSize}px` }"
        >
          <slot :item="entry.item" :index="entry.index" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" generic="T extends { key: string }">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    items: T[]
    itemSize?: number
    height?: number
    overscan?: number
  }>(),
  {
    itemSize: 96,
    height: 560,
    overscan: 6
  }
)

const root = ref<HTMLElement | null>(null)
const scrollTop = ref(0)

const totalHeight = computed(() => props.items.length * props.itemSize)

const startIndex = computed(() =>
  Math.max(0, Math.floor(scrollTop.value / props.itemSize) - props.overscan)
)

const endIndex = computed(() => {
  const visible = Math.ceil(props.height / props.itemSize) + props.overscan * 2
  return Math.min(props.items.length, startIndex.value + visible)
})

const offsetY = computed(() => startIndex.value * props.itemSize)

const visible = computed(() =>
  props.items.slice(startIndex.value, endIndex.value).map((item, i) => ({
    item,
    index: startIndex.value + i,
    key: item.key
  }))
)

function onScroll(e: Event) {
  scrollTop.value = (e.target as HTMLElement).scrollTop
}

function onWheel() {
  /* keep reactive scrollTop via @scroll */
}

onMounted(() => {
  root.value?.addEventListener('wheel', onWheel, { passive: true })
})

onBeforeUnmount(() => {
  root.value?.removeEventListener('wheel', onWheel)
})
</script>

<style scoped>
.virtual-list {
  overflow: auto;
  position: relative;
  border: 1px solid #243041;
  border-radius: 10px;
  background: color-mix(in srgb, var(--panel, #111827) 92%, black 8%);
}
.virtual-list__spacer {
  position: relative;
  width: 100%;
}
.virtual-list__window {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  will-change: transform;
}
.virtual-list__row {
  box-sizing: border-box;
  padding: 6px 10px;
  overflow: hidden;
}
</style>
