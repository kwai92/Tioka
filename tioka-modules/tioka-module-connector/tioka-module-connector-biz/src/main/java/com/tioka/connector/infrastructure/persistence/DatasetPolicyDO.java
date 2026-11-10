package com.tioka.connector.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.OffsetDateTime;

/** data_dataset_policy 行（V163 / DAT-01，复合主键 dataset_id + version；@TableId 按惯例只标首列）。 */
@TableName("data_dataset_policy")
public class DatasetPolicyDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String datasetId;
    private Integer version;
    private String rowsJson;
    private String colsJson;
    private String mode;
    private String status;
    private String createdBy;
    private OffsetDateTime createdAt;

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getRowsJson() { return rowsJson; }
    public void setRowsJson(String rowsJson) { this.rowsJson = rowsJson; }
    public String getColsJson() { return colsJson; }
    public void setColsJson(String colsJson) { this.colsJson = colsJson; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
