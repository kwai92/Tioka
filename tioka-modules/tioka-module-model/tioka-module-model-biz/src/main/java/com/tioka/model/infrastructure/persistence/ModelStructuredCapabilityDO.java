package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.MutableRecordDO;

/**
 * SCH-06（ADR-066 §4；V177 {@code model_structured_capability}）：模型结构化输出能力记录。
 *
 * <p>主键 {@code model_ref}（口径 {@code logicalModelId@modelName}，与 {@code ModelBindingResolver} 一致）。
 * JSONB 列按仓库惯例映射为 String（读取经 JDBC 文本化，写入经 XML mapper 显式 {@code ::jsonb} 转型）。
 * 无 workspace_id 列，旁路 ScopeLine。
 */
@TableName("model_structured_capability")
public class ModelStructuredCapabilityDO extends MutableRecordDO {

    @TableId(value = "model_ref", type = IdType.INPUT)
    private String modelRef;
    private String version;
    /** JSON 数组：StructuredOutputMode 名称集合。 */
    private String modes;
    /** JSON 数组：严格档接受的关键字集合。 */
    private String keywords;
    /** JSON 对象：Limits。 */
    private String limits;
    /** JSON 对象：StructuredOutputMode → ThinkingCompat。 */
    private String thinkingCompat;
    private Boolean multimodalDowngrade;
    private Boolean promptKeyword;
    private String maxTokensAdvice;
    private String stability;
    private String refusalSignal;
    private String truncationSignal;
    private String verifiedAt;
    private String conformance;

    public String getModelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getModes() { return modes; }
    public void setModes(String modes) { this.modes = modes; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getLimits() { return limits; }
    public void setLimits(String limits) { this.limits = limits; }
    public String getThinkingCompat() { return thinkingCompat; }
    public void setThinkingCompat(String thinkingCompat) { this.thinkingCompat = thinkingCompat; }
    public Boolean getMultimodalDowngrade() { return multimodalDowngrade; }
    public void setMultimodalDowngrade(Boolean multimodalDowngrade) { this.multimodalDowngrade = multimodalDowngrade; }
    public Boolean getPromptKeyword() { return promptKeyword; }
    public void setPromptKeyword(Boolean promptKeyword) { this.promptKeyword = promptKeyword; }
    public String getMaxTokensAdvice() { return maxTokensAdvice; }
    public void setMaxTokensAdvice(String maxTokensAdvice) { this.maxTokensAdvice = maxTokensAdvice; }
    public String getStability() { return stability; }
    public void setStability(String stability) { this.stability = stability; }
    public String getRefusalSignal() { return refusalSignal; }
    public void setRefusalSignal(String refusalSignal) { this.refusalSignal = refusalSignal; }
    public String getTruncationSignal() { return truncationSignal; }
    public void setTruncationSignal(String truncationSignal) { this.truncationSignal = truncationSignal; }
    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getConformance() { return conformance; }
    public void setConformance(String conformance) { this.conformance = conformance; }
}
