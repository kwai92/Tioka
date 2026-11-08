package com.tioka.framework.security;

import java.util.List;

/**
 * 主密钥轮换时按「表 + 列」登记需重加密的凭证列（约束 F19）。
 * M3 的供应商凭证、MCP 令牌、数据源凭证直接 {@link #register} 进来。
 */
public interface CredentialReencryptRegistry {

    void register(CredentialColumn column);

    List<CredentialColumn> columns();

    /** @param table 逻辑表名 @param column 密文列名 */
    record CredentialColumn(String table, String column) {}
}
