package com.tioka.runtime.infrastructure;

import com.tioka.runtime.application.tool.DailyCounterStore;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** TRU-02 当日累计重算数据源：trust_action（当天 APPLIED 且未撤销）。 */
@Component
public class TrustActionDailySource implements DailyCounterStore.AppliedActionsSource {

    private final TrustActionRepository repository;

    public TrustActionDailySource(TrustActionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AppliedAction> list(String agentId, String actionClass, LocalDate dayUtc) {
        List<TrustActionDO> rows = repository.listAppliedInDay(agentId, actionClass,
                dayUtc.atStartOfDay(), dayUtc.plusDays(1).atStartOfDay());
        List<AppliedAction> out = new ArrayList<>();
        for (TrustActionDO row : rows) {
            out.add(new AppliedAction(row.getAmountMicros() == null ? 0 : row.getAmountMicros(),
                    row.getRecords() == null ? 0 : row.getRecords()));
        }
        return out;
    }
}
