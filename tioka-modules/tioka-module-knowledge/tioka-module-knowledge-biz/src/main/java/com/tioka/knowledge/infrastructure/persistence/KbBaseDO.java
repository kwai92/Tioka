package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.WorkspaceAwareDO;

@TableName("kb_base")
public class KbBaseDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String embeddingBindingId;
    /** DAT-02（V164）：知识库数据标签，缺省 INTERNAL。 */
    private String dataLabel;
    /** KNW-03 / V179：切块策略 JSON（缺省 NULL = 自动）；修改后经重建任务生效。 */
    private String chunkPolicyJson;
    /** KNW-03 / V179：上下文增强开关（子块嵌入/全文索引加标题路径前缀），缺省 TRUE。 */
    private Boolean contextEnhancement;
    /** KNW-04 / V227：知识库绑定的 rerank 模型；NULL = 未绑定（跳过且不设阈值）。 */
    private String rerankBindingId;
    /** KNW-04 / V227：rerank 分数阈值，NULL 时取缺省 0.3。 */
    private Double rerankThreshold;

    public String getRerankBindingId() { return rerankBindingId; }
    public void setRerankBindingId(String rerankBindingId) { this.rerankBindingId = rerankBindingId; }
    public Double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(Double rerankThreshold) { this.rerankThreshold = rerankThreshold; }

    public String getChunkPolicyJson() { return chunkPolicyJson; }
    public void setChunkPolicyJson(String chunkPolicyJson) { this.chunkPolicyJson = chunkPolicyJson; }
    public Boolean getContextEnhancement() { return contextEnhancement; }
    public void setContextEnhancement(Boolean contextEnhancement) { this.contextEnhancement = contextEnhancement; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmbeddingBindingId() { return embeddingBindingId; }
    public void setEmbeddingBindingId(String embeddingBindingId) { this.embeddingBindingId = embeddingBindingId; }
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
}
