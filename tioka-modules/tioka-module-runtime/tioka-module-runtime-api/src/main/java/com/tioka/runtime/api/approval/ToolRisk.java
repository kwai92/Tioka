package com.tioka.runtime.api.approval;

/**
 * 工具风险等级（RT-07，施工设计 M1-02）。默认审批策略按此决定是否需要人审：
 * READ 自动执行，其余需要审批，除非 Agent 版本对该工具另有配置。
 */
public enum ToolRisk {
    /** 只读，无副作用。 */
    READ,
    /** 修改内部或外部数据。 */
    WRITE,
    /** 删除或不可逆操作。 */
    DESTRUCTIVE,
    /** 向外部发送内容（邮件、IM、Webhook）。 */
    EXTERNAL_SEND
}
