package com.tioka.framework.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "tioka.persistence")
public class PersistenceProperties {
    /**
     * ScopeLine <b>旁路</b>表白名单（deny-by-default）。
     * 列入者不加 {@code workspace_id} 过滤：无该列的表，或鉴权路径常无 Principal 的表。
     * 未列入且带 {@code workspace_id} 的表一律过滤。
     * 无 workspace 的 SYSTEM Principal 仍整表旁路（后台扫表）。
     */
    private List<String> workspaceScopeExemptTables = new ArrayList<>(List.of(
            // 鉴权例外（有 workspace_id 列，但登录/API Key 常无用户 Principal）
            "sys_member",
            "sys_api_key",
            // 无 workspace_id 列（迁移盘点 2026-11-07）
            "agent_draft",
            "agent_marketplace",
            "agent_version",
            "auth_user",
            "automation_job",
            "chat_memory",
            "chat_message",
            "decision_binding",
            "feishu_doc_map",
            "feishu_doc_push",
            "flow_publication",
            "flow_run",
            "flow_version",
            "gov_quota",
            "im_outbox",
            "kb_citation",
            "kb_datasource",
            "kb_document",
            "kb_wiki",
            "mcp_tool",
            "model_binding",
            "model_alias",
            "model_provider",
            "model_routing_plan",
            "model_turn_freeze",
            // M0 旧编排表（V132 已 DROP；无 workspace_id）
            "orch_attention",
            "orch_idem",
            "orch_why",
            // V132：无 workspace_id 的编排新表（orch_rule / orch_run 等有列，不得 exempt）
            "orch_rule_version",
            "orch_decision",
            "orch_mandate",
            "orch_subscription",
            "orch_fact",
            "runtime_idem",
            // MAS-01 / V160：委派授予（会话级内部对象，经 runtime_thread.grant_id 关联，无 workspace_id）
            "rt_grant",
            "runtime_item",
            "runtime_turn",
            "scim_user",
            "secret_store",
            "sys_group",
            "sys_group_member",
            "sys_resource_acl",
            "sys_role",
            "sys_role_permission",
            "sys_user_role",
            "sys_workspace",
            "trace_span",
            // ORC-02 / V130：平台 outbox / 消费去重 / 死信（无 workspace_id）
            "sys_outbox",
            "sys_consumed",
            "sys_dead_letter",
            // GOV-12 / V150：配置版本框架（无 workspace_id）
            "cfg_draft",
            "cfg_version",
            // FND-08 / V141：代用户令牌保管（用户个人凭证，无 workspace_id）
            "sys_user_credential",
            // TRU-09 / V203：自治等级（等级 per Agent×类别，Agent 全局唯一，无 workspace_id）
            "trust_autonomy",
            "trust_autonomy_event",
            "trust_autonomy_stats",
            // DAT-01 / V163：行列策略（PK dataset_id+version，随数据集归属，无 workspace_id）
            "data_dataset_policy"
    ));

    public List<String> getWorkspaceScopeExemptTables() {
        return workspaceScopeExemptTables;
    }

    public void setWorkspaceScopeExemptTables(List<String> workspaceScopeExemptTables) {
        this.workspaceScopeExemptTables = workspaceScopeExemptTables;
    }
}
