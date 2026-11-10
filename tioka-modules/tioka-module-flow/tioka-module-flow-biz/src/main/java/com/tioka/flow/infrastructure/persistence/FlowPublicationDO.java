package com.tioka.flow.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * FlowPublication 持久化（47-GL §1.6）；物理表 {@code flow_publication}（V129 自 flow_tool_pub 重命名）。
 */
@TableName("flow_publication")
public class FlowPublicationDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String toolName;
    private String versionId;
    /** DAT-04（V165）：发布时计算的 requiredCapabilities。 */
    private String requiredCapabilitiesJson;

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getRequiredCapabilitiesJson() { return requiredCapabilitiesJson; }
    public void setRequiredCapabilitiesJson(String requiredCapabilitiesJson) { this.requiredCapabilitiesJson = requiredCapabilitiesJson; }
}
