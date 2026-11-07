/**
 * F27：审批主体渲染注册表。
 * M3 新增主体时只登记新渲染器，不改审批台布局。
 */
import type { Component } from 'vue'
import TurnSubjectCard from './subjects/TurnSubjectCard.vue'
import OrchRunSubjectCard from './subjects/OrchRunSubjectCard.vue'
import FlowNodeRunSubjectCard from './subjects/FlowNodeRunSubjectCard.vue'
import UnknownSubjectCard from './subjects/UnknownSubjectCard.vue'
import type { ApprovalSubjectType } from '@/api/approval'

export const APPROVAL_SUBJECT_TYPES: ApprovalSubjectType[] = [
  'turn',
  'orch_run',
  'flow_node_run'
]

export const SUBJECT_LABELS: Record<ApprovalSubjectType, string> = {
  turn: '对话',
  orch_run: '编排',
  flow_node_run: 'Flow'
}

export const approvalSubjectRenderers: Record<string, Component> = {
  turn: TurnSubjectCard,
  orch_run: OrchRunSubjectCard,
  flow_node_run: FlowNodeRunSubjectCard
}

export function resolveApprovalSubjectRenderer(subjectType: string): Component {
  return approvalSubjectRenderers[subjectType] ?? UnknownSubjectCard
}

export function registerApprovalSubjectRenderer(
  subjectType: string,
  component: Component
): void {
  approvalSubjectRenderers[subjectType] = component
}
