package com.tioka.connector.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
/** DocSyncMapping 现行落点（47-GL §1.8）；表名暂 feishu 专用。 */
@TableName("feishu_doc_map")
public class FeishuDocMapDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String localDocId;
    private String feishuDocToken;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLocalDocId() { return localDocId; }
    public void setLocalDocId(String localDocId) { this.localDocId = localDocId; }
    public String getFeishuDocToken() { return feishuDocToken; }
    public void setFeishuDocToken(String feishuDocToken) { this.feishuDocToken = feishuDocToken; }
}
