/**
 * SCH-04 / FE-00：`data` item 按 Schema 注册渲染器（P3-00c 登记点）。
 * 未注册 schemaId 时由 DataItem 回落 JSON 树，不抛错。
 */
import type { Component } from 'vue'

const schemaRenderers: Record<string, Component> = {}

export function registerDataItemRenderer(schemaId: string, component: Component): void {
  schemaRenderers[schemaId] = component
}

export function resolveDataItemRenderer(schemaId: string | undefined | null): Component | null {
  if (!schemaId) return null
  return schemaRenderers[schemaId] ?? null
}

export function clearDataItemRenderers(): void {
  for (const key of Object.keys(schemaRenderers)) {
    delete schemaRenderers[key]
  }
}

export { schemaRenderers }
