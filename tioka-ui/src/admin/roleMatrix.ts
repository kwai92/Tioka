/** HCI-03：角色权限矩阵 — 按资源前缀分组与 payload 组装。 */

export type PermissionGroup = {
  resource: string
  permissions: string[]
}

/** 将扁平权限点按 `resource:` 前缀分组（稳定排序）。 */
export function groupPermissionsByResource(catalog: string[]): PermissionGroup[] {
  const map = new Map<string, string[]>()
  for (const p of catalog) {
    const i = p.indexOf(':')
    const resource = i > 0 ? p.slice(0, i) : p
    const list = map.get(resource) ?? []
    list.push(p)
    map.set(resource, list)
  }
  return [...map.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([resource, permissions]) => ({
      resource,
      permissions: [...permissions].sort((x, y) => x.localeCompare(y))
    }))
}

/** 勾选集合 → PUT body.permissions（去重 + 排序）。 */
export function buildRolePermissionPayload(selected: Iterable<string>): string[] {
  return [...new Set(selected)].filter(Boolean).sort((a, b) => a.localeCompare(b))
}

export function togglePermission(selected: Set<string>, perm: string, checked: boolean): Set<string> {
  const next = new Set(selected)
  if (checked) next.add(perm)
  else next.delete(perm)
  return next
}
