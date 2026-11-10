package com.tioka.governance.infrastructure.label;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tioka.governance.application.label.LabelSinkRepository;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.governance.infrastructure.mapper.GovLabelSinkMapper;
import com.tioka.governance.infrastructure.persistence.GovLabelSinkDO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** {@code gov_label_sink} 仓储实现（DAT-02）。 */
@Repository
public class GovLabelSinkRepository implements LabelSinkRepository {

    private final GovLabelSinkMapper mapper;

    public GovLabelSinkRepository(GovLabelSinkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LabelSinkRule> find(String workspaceId, SinkKind kind, String ref) {
        GovLabelSinkDO row = mapper.selectOne(new LambdaQueryWrapper<GovLabelSinkDO>()
                .eq(GovLabelSinkDO::getWorkspaceId, workspaceId)
                .eq(GovLabelSinkDO::getSinkKind, kind.name())
                .eq(GovLabelSinkDO::getSinkRef, ref == null ? "" : ref));
        return Optional.ofNullable(row).map(r -> new LabelSinkRule(
                r.getWorkspaceId(), SinkKind.valueOf(r.getSinkKind()), r.getSinkRef(),
                r.getMaxLabel(), r.getOnExceed()));
    }

    @Override
    public void save(LabelSinkRule rule) {
        GovLabelSinkDO row = new GovLabelSinkDO();
        row.setWorkspaceId(rule.workspaceId());
        row.setSinkKind(rule.kind().name());
        row.setSinkRef(rule.ref() == null ? "" : rule.ref());
        row.setMaxLabel(rule.maxLabel());
        row.setOnExceed(rule.onExceed());
        if (mapper.selectOne(new LambdaQueryWrapper<GovLabelSinkDO>()
                .eq(GovLabelSinkDO::getWorkspaceId, rule.workspaceId())
                .eq(GovLabelSinkDO::getSinkKind, rule.kind().name())
                .eq(GovLabelSinkDO::getSinkRef, row.getSinkRef())) == null) {
            mapper.insert(row);
        } else {
            mapper.update(row, new LambdaQueryWrapper<GovLabelSinkDO>()
                    .eq(GovLabelSinkDO::getWorkspaceId, rule.workspaceId())
                    .eq(GovLabelSinkDO::getSinkKind, rule.kind().name())
                    .eq(GovLabelSinkDO::getSinkRef, row.getSinkRef()));
        }
    }
}
