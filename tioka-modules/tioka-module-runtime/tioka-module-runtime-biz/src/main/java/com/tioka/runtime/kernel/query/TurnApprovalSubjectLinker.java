package com.tioka.runtime.kernel.query;

import com.tioka.runtime.api.approval.ApprovalSubjectLinker;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.stereotype.Component;

/**
 * F27：turn 主体跳转到会话。
 */
@Component
public class TurnApprovalSubjectLinker implements ApprovalSubjectLinker {

    private final TurnRepository turns;

    public TurnApprovalSubjectLinker(TurnRepository turns) {
        this.turns = turns;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectType.TURN.wire();
    }

    @Override
    public ApprovalView.SubjectLink link(String subjectId, String titleHint) {
        String threadId = turns.findById(subjectId).map(RuntimeTurnDO::getThreadId).orElse(null);
        String url = threadId == null ? null : "/threads/" + threadId;
        String title = titleHint == null || titleHint.isBlank() ? subjectId : titleHint;
        return new ApprovalView.SubjectLink(subjectType(), subjectId, title, url);
    }
}
