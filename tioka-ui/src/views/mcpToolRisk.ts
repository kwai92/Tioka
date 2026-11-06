/** FE-01：MCP 工具风险四态（对齐 V114 / ToolRisk）。 */
export const MCP_TOOL_RISKS = ['READ', 'WRITE', 'DESTRUCTIVE', 'EXTERNAL_SEND'] as const

export type McpToolRisk = (typeof MCP_TOOL_RISKS)[number]

export type PatchToolRiskApi = (
  path: string,
  opts?: RequestInit
) => Promise<{ name: string; risk: string }>

/** 改 risk 即调 PATCH；供 Mcp.vue 与 vitest 共用。 */
export async function patchToolRisk(
  api: PatchToolRiskApi,
  serverId: string,
  toolName: string,
  risk: McpToolRisk
): Promise<{ name: string; risk: string }> {
  return api(`/api/mcp/servers/${encodeURIComponent(serverId)}/tools/${encodeURIComponent(toolName)}`, {
    method: 'PATCH',
    body: JSON.stringify({ risk })
  })
}
