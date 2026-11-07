package com.tioka.server.gov;

/**
 * ADR-072 §5：审计覆盖白名单仅允许四类；「待补」非法。
 */
enum WhitelistKind {
    /** 公开认证面，由 AuthService 等手写 auth.* */
    PUBLIC_AUTH,
    /** 语义只读的 POST（检索/探测等） */
    READ_ONLY_POST,
    /** 明确未实现（501 等） */
    NOT_IMPLEMENTED,
    /** 入站回调，记外部交互台账（ADR-074） */
    INBOUND_LEDGER
}
