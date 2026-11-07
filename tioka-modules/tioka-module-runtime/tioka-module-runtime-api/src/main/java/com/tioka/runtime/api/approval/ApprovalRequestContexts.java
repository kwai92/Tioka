package com.tioka.runtime.api.approval;

import com.tioka.framework.security.Principal;
import com.tioka.runtime.api.spi.ToolProvider;

import java.util.Map;

/**
 * {@link ApprovalPolicy.ApprovalRequestContext} 唯一工厂（M1-00 F3）。
 * 调用方禁止直接 {@code new ApprovalRequestContext(...)}；M3 扩展字段只改本工厂。
 */
public final class ApprovalRequestContexts {
    private ApprovalRequestContexts() {}

    public static ApprovalPolicy.ApprovalRequestContext from(Principal principal,
                                                             String agentVersionId,
                                                             String toolName,
                                                             Map<String, Object> args,
                                                             ToolProvider.ToolDescriptor descriptor,
                                                             Map<String, String> options) {
        ToolRisk risk = descriptor == null || descriptor.risk() == null
                ? ToolRisk.READ : descriptor.risk();
        return new ApprovalPolicy.ApprovalRequestContext(
                principal,
                agentVersionId,
                toolName,
                args == null ? Map.of() : Map.copyOf(args),
                risk,
                options == null ? Map.of() : Map.copyOf(options));
    }
}
