package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

/**
 * {@code gov_label_sink}（V164 / ADR-040 数据模型）：去向许可规则。
 * 复合主键（workspace_id, sink_kind, sink_ref）不走 {@code @TableId}，查询一律经仓储显式条件。
 */
@TableName("gov_label_sink")
public class GovLabelSinkDO extends TechnicalRecordDO {

    private String workspaceId;
    private String sinkKind;
    private String sinkRef;
    private String maxLabel;
    private String onExceed;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getSinkKind() { return sinkKind; }
    public void setSinkKind(String sinkKind) { this.sinkKind = sinkKind; }
    public String getSinkRef() { return sinkRef; }
    public void setSinkRef(String sinkRef) { this.sinkRef = sinkRef; }
    public String getMaxLabel() { return maxLabel; }
    public void setMaxLabel(String maxLabel) { this.maxLabel = maxLabel; }
    public String getOnExceed() { return onExceed; }
    public void setOnExceed(String onExceed) { this.onExceed = onExceed; }
}
