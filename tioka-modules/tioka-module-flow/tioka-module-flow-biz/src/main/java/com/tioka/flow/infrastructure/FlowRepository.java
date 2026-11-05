package com.tioka.flow.infrastructure;

import com.tioka.flow.infrastructure.mapper.FlowDraftMapper;
import com.tioka.flow.infrastructure.mapper.FlowRunMapper;
import com.tioka.flow.infrastructure.mapper.FlowToolPubMapper;
import com.tioka.flow.infrastructure.mapper.FlowVersionMapper;
import com.tioka.flow.infrastructure.persistence.FlowDraftDO;
import com.tioka.flow.infrastructure.persistence.FlowRunDO;
import com.tioka.flow.infrastructure.persistence.FlowToolPubDO;
import com.tioka.flow.infrastructure.persistence.FlowVersionDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FlowRepository {
    private final FlowDraftMapper drafts;
    private final FlowVersionMapper versions;
    private final FlowRunMapper runs;
    private final FlowToolPubMapper pubs;

    public FlowRepository(FlowDraftMapper drafts, FlowVersionMapper versions,
                          FlowRunMapper runs, FlowToolPubMapper pubs) {
        this.drafts = drafts;
        this.versions = versions;
        this.runs = runs;
        this.pubs = pubs;
    }

    public void insertDraft(FlowDraftDO row) { drafts.insert(row); }
    public FlowDraftDO findDraft(String id) { return drafts.selectById(id); }
    public void insertVersion(FlowVersionDO row) { versions.insert(row); }
    public FlowVersionDO findVersion(String id) { return versions.selectById(id); }
    public void insertRun(FlowRunDO row) { runs.insert(row); }
    public void upsertToolPub(String toolName, String versionId) {
        FlowToolPubDO row = pubs.selectById(toolName);
        if (row == null) {
            row = new FlowToolPubDO();
            row.setToolName(toolName);
            row.setVersionId(versionId);
            pubs.insert(row);
        } else {
            row.setVersionId(versionId);
            pubs.updateById(row);
        }
    }
    public List<FlowToolPubDO> listToolPubs() { return pubs.selectList(null); }
    public FlowToolPubDO findToolPub(String toolName) { return pubs.selectById(toolName); }
}
