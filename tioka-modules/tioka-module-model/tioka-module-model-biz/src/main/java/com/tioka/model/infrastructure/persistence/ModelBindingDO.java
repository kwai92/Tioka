package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("model_binding")
public class ModelBindingDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String logicalModelId;
    private String providerId;
    private String modelName;
    private String kind;
    private Integer contextWindow;
    private Integer embeddingDim;
    private Long inputPriceMicros;
    private Long outputPriceMicros;
    private Integer rpmLimit;
    private Integer tpmLimit;
    /** P2-L5（V218）：本月 token 预算上限；NULL = 不限。 */
    private Long budgetTokenMonth;
    /** P2-L5（V218）：本月成本预算上限（微美分）；NULL = 不限。 */
    private Long budgetCostMonth;
    private Boolean enabled;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(String logicalModelId) { this.logicalModelId = logicalModelId; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }
    public Integer getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }
    public Long getInputPriceMicros() { return inputPriceMicros; }
    public void setInputPriceMicros(Long inputPriceMicros) { this.inputPriceMicros = inputPriceMicros; }
    public Long getOutputPriceMicros() { return outputPriceMicros; }
    public void setOutputPriceMicros(Long outputPriceMicros) { this.outputPriceMicros = outputPriceMicros; }
    public Integer getRpmLimit() { return rpmLimit; }
    public void setRpmLimit(Integer rpmLimit) { this.rpmLimit = rpmLimit; }
    public Integer getTpmLimit() { return tpmLimit; }
    public void setTpmLimit(Integer tpmLimit) { this.tpmLimit = tpmLimit; }
    public Long getBudgetTokenMonth() { return budgetTokenMonth; }
    public void setBudgetTokenMonth(Long budgetTokenMonth) { this.budgetTokenMonth = budgetTokenMonth; }
    public Long getBudgetCostMonth() { return budgetCostMonth; }
    public void setBudgetCostMonth(Long budgetCostMonth) { this.budgetCostMonth = budgetCostMonth; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
