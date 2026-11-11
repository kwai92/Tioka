package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("kb_chunk")
public class KbChunkDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String docId;
    private String kbId;
    /** V118：冗余空间 ID，供检索 ACL / 行级过滤下推。 */
    private String workspaceId;
    private String text;
    private Integer ordinal;
    /** KNW-05 / V211：分块所属文档版本；检索只看 kb_document.active_version。 */
    private Integer docVersion;
    /** KNW-03 / V179：块类型 PARENT / CHILD；父块无向量，仅供上下文组装。 */
    private String chunkType;
    /** KNW-03 / V179：子块所属父块 ID；父块该列为 NULL。 */
    private String parentChunkId;
    /** KNW-03 / V179：起始页码。 */
    private Integer pageStart;
    /** KNW-03 / V179：结束页码。 */
    private Integer pageEnd;
    /** KNW-03 / V179：来源 blockId 列表 JSON（坐标经 blockId 从解析结果取）。 */
    private String blockIdsJson;
    /** KNW-03 / V179：标题路径上下文前缀（文档标题 > 各级标题；嵌入与全文索引用，展示不加）。 */
    private String contextPrefix;

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    public String getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(String parentChunkId) { this.parentChunkId = parentChunkId; }
    public Integer getPageStart() { return pageStart; }
    public void setPageStart(Integer pageStart) { this.pageStart = pageStart; }
    public Integer getPageEnd() { return pageEnd; }
    public void setPageEnd(Integer pageEnd) { this.pageEnd = pageEnd; }
    public String getBlockIdsJson() { return blockIdsJson; }
    public void setBlockIdsJson(String blockIdsJson) { this.blockIdsJson = blockIdsJson; }
    public String getContextPrefix() { return contextPrefix; }
    public void setContextPrefix(String contextPrefix) { this.contextPrefix = contextPrefix; }

    public Integer getDocVersion() { return docVersion; }
    public void setDocVersion(Integer docVersion) { this.docVersion = docVersion; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
}
