package com.tioka.runtime.application.tool;

import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.spi.GrantCheck;
import com.tioka.runtime.api.spi.ToolGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用管线第 2.5 步「授予」真实现（ADR-039 / M3-01 MAS-01），替换 M1 恒放行占位。
 *
 * <p>{@code ctx.grantId == null} 视为根授予尚未签发（仅测试 / 旧链路），放行。
 * 拒绝返回非空原因，拒绝步号记 step=2，统一 {@code ToolResult(false,...)} 不终止 Turn。
 *
 * <p>TODO（TRU-02 阻塞，本批 stub 放行并登记）：
 * ① 非 READ 工具的动作类别检查（{@code Classified.actionClass} 依赖 Charter 编译器）；
 * ② descriptor 无 resourceArgs 元数据，mcp/flow/dataset 资源 ID 归属无法识别，
 *    仅 kb（kbId/kbIds 参数键，M1-04 口径）做交集剔除。
 */
@Component
public class DelegationGrantCheck implements GrantCheck {

    private final GrantService grants;

    public DelegationGrantCheck(GrantService grants) {
        this.grants = grants;
    }

    @Override
    public String check(ToolGateway.ToolCallContext ctx, String toolName, Map<String, Object> args) {
        if (ctx == null || ctx.grantId() == null || ctx.grantId().isBlank()) {
            return null;
        }
        DelegationGrant grant = grants.get(ctx.grantId());
        if (grant == null || grant.status() != GrantStatus.ACTIVE
                || (grant.expiresAt() != null && Instant.now().isAfter(grant.expiresAt()))) {
            return "GRANT_INACTIVE: 委派授予已失效";
        }
        if (toolName == null || !grant.tools().contains(toolName)) {
            return "GRANT_DENIED: 不在委派授予内";
        }

        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        // kb：按交集剔除（M1-04 口径），剔除后为空 → DENIED
        Set<String> kbAllowed = grant.resources() == null ? Set.of()
                : grant.resources().getOrDefault("kb", Set.of());
        if (!kbAllowed.isEmpty() && (safeArgs.containsKey("kbId") || safeArgs.containsKey("kbIds"))) {
            List<String> kept = intersectKb(safeArgs, kbAllowed);
            if (kept.isEmpty()) {
                return "GRANT_DENIED: 知识库不在授予内";
            }
            if (args != null) {
                applyKbFilter(args, kept);
            }
        }

        // scope：参数取值越界 → DENIED
        if (grant.scope() != null) {
            for (Map.Entry<String, List<String>> e : grant.scope().entrySet()) {
                Object v = safeArgs.get(e.getKey());
                if (v == null) {
                    continue;
                }
                if (!e.getValue().contains(String.valueOf(v))) {
                    return "GRANT_DENIED: 超出任务范围 " + e.getKey();
                }
            }
        }

        // 动作类别（TRU-02 阻塞）：本批 stub 放行，批次文档登记
        return null;
    }

    private List<String> intersectKb(Map<String, Object> args, Set<String> kbAllowed) {
        List<String> out = new ArrayList<>();
        Object multi = args.get("kbIds");
        if (multi instanceof List<?> list) {
            for (Object o : list) {
                String id = String.valueOf(o);
                if (kbAllowed.contains(id) && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        Object single = args.get("kbId");
        if (single != null) {
            String id = String.valueOf(single);
            if (kbAllowed.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyKbFilter(Map<String, Object> args, List<String> kept) {
        Map<String, Object> mutable = args;
        if (mutable.containsKey("kbIds")) {
            mutable.put("kbIds", new ArrayList<>(kept));
        }
        if (mutable.containsKey("kbId")) {
            if (kept.isEmpty()) {
                mutable.remove("kbId");
            } else {
                mutable.put("kbId", kept.get(0));
            }
        }
    }
}
