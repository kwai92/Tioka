package com.tioka.governance.application.charter;

import com.tioka.framework.id.Ids;
import com.tioka.governance.api.charter.CharterEventWriter;
import com.tioka.governance.infrastructure.charter.TrustCharterEventRepository;
import com.tioka.governance.infrastructure.persistence.TrustCharterEventDO;
import org.springframework.transaction.annotation.Transactional;

/** TRU-01：授权书事件写入；生命周期状态机由 TRU-03 接线。由 CharterAutoConfiguration 装配。 */
public class DefaultCharterEventWriter implements CharterEventWriter {

    private static final String ID_PREFIX = "chev";

    private final TrustCharterEventRepository events;

    public DefaultCharterEventWriter(TrustCharterEventRepository events) {
        this.events = events;
    }

    @Override
    @Transactional
    public void append(AppendCommand command) {
        TrustCharterEventDO row = new TrustCharterEventDO();
        row.setId(Ids.newId(ID_PREFIX));
        row.setWorkspaceId(command.workspaceId());
        row.setAgentId(command.agentId());
        row.setKind(command.kind().name());
        row.setReason(command.reason());
        row.setFromOwner(command.fromOwner());
        row.setToOwner(command.toOwner());
        row.setActor(command.actor());
        events.insert(row);
    }
}
