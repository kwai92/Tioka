package com.tioka.governance.api.policy;

import java.util.List;

/**
 * GOV-07 管理接口（M3-05 §1.1）：策略列表与详情、导入（Cedar 文本）、切换模式、停用。
 * M3 只提供接口与导入，空间级策略编辑界面在 M4；实现与权限校验在 governance-biz。
 */
public interface PolicyAdminApi {

    List<PolicyView> list(String workspaceId, String source);

    PolicyView detail(String policyRowId);

    /** 导入一条 forbid 策略（Cedar 文本）；permit 或解析失败一律拒绝；模式默认取 {@code tioka.policy.import-default-mode}。 */
    PolicyView importPolicy(ImportCommand cmd);

    /** 切换 DRYRUN / ENFORCE；需权限点 {@code gov:policy:admin}，写审计，并广播策略变更事件。 */
    PolicyView setMode(String policyRowId, PolicyMode mode);

    /** 停用（参与求值的策略集随即不再包含该条）；写审计并广播策略变更事件。 */
    PolicyView disable(String policyRowId);

    record ImportCommand(String workspaceId, String cedarText, PolicyMode mode, String createdBy) {}

    record PolicyView(String id, String workspaceId, int version, String source, String policyId,
                      String cedarText, PolicyMode mode, PolicyDecision effect, String status) {}
}
