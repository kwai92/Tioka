package com.tioka.connector.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

/** data_dataset 行（V163 / DAT-01）。 */
@TableName("data_dataset")
public class DatasetDO extends WorkspaceAwareDO {
    @TableId
    private String id;
    private String connectorId;
    private String name;
    private String sourceJson;
    private String columnsJson;
    private String status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceJson() { return sourceJson; }
    public void setSourceJson(String sourceJson) { this.sourceJson = sourceJson; }
    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String columnsJson) { this.columnsJson = columnsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
