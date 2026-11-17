package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.foundation.starter.data.AppendOnlyDO;

@TableName("runtime_item")
public class RuntimeItemDO extends AppendOnlyDO {
    private String threadId;
    private Long seq;
    private String id;
    private String turnId;
    private Integer step;
    private String type;
    private String role;
    private String contentJson;
    private Integer tokenCount;
    /** DAT-02（V164）：工具结果携带的数据标签（M3-02）；null = 未打标。 */
    private String dataLabel;
    private Boolean discarded;
    /** SCH-07（V232）：data item 的权威 Schema 引用（精确到版本）；非 data item 为 null。 */
    private String schemaRef;
    /** SCH-07（V232）：四种结局（VALID / VALID_WITH_UNKNOWN / HUMAN_PENDING / FAILED）；非 data item 为 null。 */
    private String outcome;
    /** SCH-07（V232）：失败原因码（TRUNCATED / REFUSED / SCHEMA / RULE …）；成功为 null。 */
    private String reasonCode;
    /** SCH-10（V234）：data item 的产出模型 ref（producedBy.model）；非 data item 为 null。 */
    private String modelRef;
    /** SCH-10（V234）：结构化输出提交方式（OutputMode：FINAL / TOOL）；非 data item 为 null。 */
    private String outputMode;
    /** SCH-10（V234）：修复轮数（producedBy.repairRounds）；非 data item 为 null。 */
    private Integer repairRounds;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Integer getStep() { return step; }
    public void setStep(Integer step) { this.step = step; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
    public Boolean getDiscarded() { return discarded; }
    public void setDiscarded(Boolean discarded) { this.discarded = discarded; }
    public String getSchemaRef() { return schemaRef; }
    public void setSchemaRef(String schemaRef) { this.schemaRef = schemaRef; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getModelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }
    public String getOutputMode() { return outputMode; }
    public void setOutputMode(String outputMode) { this.outputMode = outputMode; }
    public Integer getRepairRounds() { return repairRounds; }
    public void setRepairRounds(Integer repairRounds) { this.repairRounds = repairRounds; }
}
