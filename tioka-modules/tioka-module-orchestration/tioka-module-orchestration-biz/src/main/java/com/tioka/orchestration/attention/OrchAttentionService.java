package com.tioka.orchestration.attention;

import com.tioka.orchestration.api.engine.Receipt;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.receipt.OrchReceiptRepository;
import com.tioka.orchestration.receipt.ReceiptFactKinds;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.approval.CardApprovalApi;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回执 ask → 打开/复用 orch_run 卡片审批（ORC-04 / PK-16）。
 */
@Service
public class OrchAttentionService {

    private final CardApprovalApi cards;
    private final OrchReceiptRepository receiptRepo;

    public OrchAttentionService(CardApprovalApi cards, OrchReceiptRepository receiptRepo) {
        this.cards = cards;
        this.receiptRepo = receiptRepo;
    }

    @Transactional
    public ApprovalView openFromAsk(OrchRunDO run, Receipt.Ask ask) {
        Duration timeout = ask.timeout() == null ? Duration.ofHours(24) : ask.timeout();
        Map<String, Object> onTimeout = ask.onTimeout() == null ? Map.of() : Map.copyOf(ask.onTimeout());
        Map<String, Object> defaultAnswer = Map.of();
        if (onTimeout.get("default") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            defaultAnswer = cast;
        }
        ApprovalView view = cards.openOrReuse(new CardApprovalApi.OpenCardCommand(
                run.getWorkspaceId(),
                ApprovalSubjectType.ORCH_RUN.wire(),
                run.getId(),
                ask.key(),
                ask.title() == null ? "编排人审" : ask.title(),
                null,
                ask.form() == null ? List.of() : ask.form(),
                toCardAssignee(ask.assignee()),
                timeout,
                defaultAnswer.isEmpty() ? null : defaultAnswer,
                null));

        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("key", ask.key());
        fact.put("title", ask.title() == null ? "" : ask.title());
        fact.put("approvalId", view.id());
        receiptRepo.appendFact(run.getId(), ReceiptFactKinds.ATTENTION_OPENED, fact);
        return view;
    }

    /** 回执 schema 用 users/roles；卡片审批 API 用 userIds/roleCodes。 */
    private static Map<String, List<String>> toCardAssignee(Map<String, List<String>> assignee) {
        if (assignee == null || assignee.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> users = assignee.getOrDefault("userIds",
                assignee.getOrDefault("users", List.of()));
        List<String> roles = assignee.getOrDefault("roleCodes",
                assignee.getOrDefault("roles", List.of()));
        out.put("userIds", users);
        out.put("roleCodes", roles);
        return out;
    }
}
