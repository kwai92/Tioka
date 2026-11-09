package com.tioka.governance.application.policy;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import com.tioka.governance.api.policy.PolicyEvalRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GOV-07 Cedar 实体/动作组映射（M3-05 §1.1 / §1.2）：每个工具是一个动作（Tioka::Action），
 * 动作类别是动作组（Cedar 动作组只能为 Action 类型的父实体层级，{@code action in
 * Tioka::Action::"{类别}"}；ADR-032 示例的 {@code Tioka::ActionClass::"..."} 写法在无 schema
 * 求值时不可解析，登记偏差——类别与工具统一落在 Action 层级，见批次文档）。
 *
 * <p>实体类型：Tioka::User（principal，属性 roles/departmentId）、Tioka::Action（动作与动作组）、
 * Tioka::Tool（resource，属性取变更单汇总/可策略化参数）。工具注册表与授权书动作类别经
 * {@link ToolActionSpec} 注入（运行时由管线第 9 步从类型化 Charter 解析）。
 */
public final class CedarSchemaProvider {

    public static final String USER_TYPE = "Tioka::User";
    public static final String ACTION_TYPE = "Tioka::Action";
    public static final String TOOL_TYPE = "Tioka::Tool";

    private final List<ToolActionSpec> actions;

    /** 工具注册表投影 + 授权书动作类别（每个工具一个动作，类别是动作组）。 */
    public CedarSchemaProvider(List<ToolActionSpec> actions) {
        this.actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 动作 EUID（principal/action/resource 的 Cedar 标识）。 */
    public EntityUID actionEuid(String toolName) {
        return euid(ACTION_TYPE, toolName == null ? "" : toolName);
    }

    public EntityUID userEuid(String principalRef) {
        return euid(USER_TYPE, principalRef == null ? "system" : principalRef);
    }

    public EntityUID toolEuid(String resourceId, String fallbackAction) {
        return euid(TOOL_TYPE, resourceId == null || resourceId.isBlank() ? fallbackAction : resourceId);
    }

    /** 动作组 EUID（Action 层级父实体）。 */
    public EntityUID actionGroupEuid(String classCode) {
        return euid(ACTION_TYPE, classCode);
    }

    /** 组装本次求值的实体：User / Action（挂在类别组下）/ Tool（resource 属性）。 */
    public Set<Entity> entitiesOf(PolicyEvalRequest request) {
        Set<Entity> entities = new HashSet<>();
        entities.add(new Entity(userEuid(request.principalRef())));
        EntityUID action = actionEuid(request.action());
        Object actionClass = request.context() == null ? null : request.context().get("actionClass");
        if (actionClass != null && !String.valueOf(actionClass).isBlank()) {
            EntityUID group = actionGroupEuid(String.valueOf(actionClass));
            entities.add(new Entity(group));
            entities.add(new Entity(action, new HashMap<>(), Set.of(group)));
        } else {
            entities.add(new Entity(action));
        }
        Map<String, Value> attrs = new HashMap<>();
        if (request.resourceAttrs() != null) {
            for (Map.Entry<String, Object> e : request.resourceAttrs().entrySet()) {
                Value v = toValue(e.getValue());
                if (v != null) {
                    attrs.put(e.getKey(), v);
                }
            }
        }
        // scope 规则口径 resource.attrs.{key}：嵌套 record 与平铺属性并存（金额/记录数直读、范围读 attrs）
        attrs.put("attrs", new com.cedarpolicy.value.CedarMap(new HashMap<>(attrs)));
        entities.add(new Entity(toolEuid(request.resourceId(), request.action()), attrs, Set.of()));
        return entities;
    }

    /** context Map → Cedar Value Map（去掉无法转换的 null）。 */
    public Map<String, Value> contextValues(Map<String, Object> raw) {
        Map<String, Value> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Value v = toValue(e.getValue());
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** 已登记动作（工具注册表投影），供档案页/校验用；求值用不到时为空。 */
    public List<ToolActionSpec> registeredActions() {
        return actions;
    }

    private static EntityUID euid(String type, String id) {
        return EntityUID.parse(type + "::\"" + sanitize(id) + "\"").orElseThrow();
    }

    private static Value toValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Value value) {
            return value;
        }
        if (v instanceof Boolean b) {
            return new PrimBool(b);
        }
        if (v instanceof Number n) {
            return new PrimLong(n.longValue());
        }
        if (v instanceof List<?> list) {
            List<Value> out = new ArrayList<>();
            for (Object o : list) {
                Value item = toValue(o);
                if (item != null) {
                    out.add(item);
                }
            }
            return new com.cedarpolicy.value.CedarList(out);
        }
        return new PrimString(String.valueOf(v));
    }

    /** EUID 标识只允许安全字符，防止 Cedar 注入。 */
    static String sanitize(String raw) {
        String s = raw == null ? "" : raw;
        return s.replaceAll("[^A-Za-z0-9._:\\-]", "_");
    }

    /** 工具动作登记（工具注册表 + 授权书动作类别的投影）。 */
    public record ToolActionSpec(String toolName, String actionClass) {}
}
