package com.tioka.runtime.kernel.approval;

import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.kernel.domain.LoopPorts;

import java.util.Map;

/** 已写入 tool_call item、待策略裁决的一次调用。 */
public record PendingToolCall(
        String itemId,
        LoopPorts.ToolCall call,
        Map<String, Object> args,
        ToolProvider.ToolDescriptor descriptor,
        ApprovalPolicy.Decision decision) {}
