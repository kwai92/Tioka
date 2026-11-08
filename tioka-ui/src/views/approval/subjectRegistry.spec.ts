import { describe, expect, it } from 'vitest'
import {
  APPROVAL_SUBJECT_TYPES,
  resolveApprovalSubjectRenderer,
  registerApprovalSubjectRenderer,
  approvalSubjectRenderers
} from './subjectRegistry'
import UnknownSubjectCard from './subjects/UnknownSubjectCard.vue'

describe('F27 approval subject registry', () => {
  it('三类主体均已登记', () => {
    expect(APPROVAL_SUBJECT_TYPES).toEqual(['turn', 'orch_run', 'flow_node_run'])
    for (const t of APPROVAL_SUBJECT_TYPES) {
      expect(approvalSubjectRenderers[t]).toBeTruthy()
      expect(resolveApprovalSubjectRenderer(t)).not.toBe(UnknownSubjectCard)
    }
  })

  it('未知主体回落到 UnknownSubjectCard', () => {
    expect(resolveApprovalSubjectRenderer('uncertain_review')).toBe(UnknownSubjectCard)
  })

  it('可登记新主体而不改布局入口', () => {
    const stub = { name: 'StubSubject' }
    registerApprovalSubjectRenderer('uncertain_review', stub)
    expect(resolveApprovalSubjectRenderer('uncertain_review')).toBe(stub)
    delete approvalSubjectRenderers.uncertain_review
  })
})
