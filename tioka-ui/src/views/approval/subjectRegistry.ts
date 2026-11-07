/**
 * F27：审批主体渲染注册表。
 * M3 新增主体时只登记新渲染器，不改审批台布局。
 */
import type { Component } from 'vue'
import TurnSubjectPlaceholder from './subjects/TurnSubjectPlaceholder.vue'
import OrchRunSubjectPlaceholder from './subjects/OrchRunSubjectPlaceholder.vue'
import FlowNodeRunSubjectPlaceholder from './subjects/FlowNodeRunSubjectPlaceholder.vue'
import UnknownSubjectPlaceholder from './subjects/UnknownSubjectPlaceholder.vue'
import type { ApprovalSubjectType } from '@/api/approval'

export const APPROVAL_SUBJECT_TYPES: ApprovalSubjectType[] = [
  'turn',
  'orch_run',
  'flow_node_run'
]

export const approvalSubjectRenderers: Record<string, Component> = {
  turn: TurnSubjectPlaceholder,
  orch_run: OrchRunSubjectPlaceholder,
  flow_node_run: FlowNodeRunSubjectPlaceholder
}

export function resolveApprovalSubjectRenderer(subjectType: string): Component {
  return approvalSubjectRenderers[subjectType] ?? UnknownSubjectPlaceholder
}

export function registerApprovalSubjectRenderer(
  subjectType: string,
  component: Component
): void {
  approvalSubjectRenderers[subjectType] = component
}
