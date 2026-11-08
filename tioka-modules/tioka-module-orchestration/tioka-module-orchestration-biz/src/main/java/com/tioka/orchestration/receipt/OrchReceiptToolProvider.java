package com.tioka.orchestration.receipt;

import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置回执工具 {@link OrchTools#RECEIPT}：经 {@link ToolProvider} 登记，不改 K1。
 * 真正生效在 Turn 结束后由 orch-turn-watcher 整体校验与落库。
 */
@Component
public class OrchReceiptToolProvider implements ToolProvider {

    public static final String PROVIDER_NAME = "orch";

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(ToolDescriptor.builder()
                .name(OrchTools.RECEIPT)
                .description("交回编排回执：DONE/NOTHING/FAILED/CANCELLED 与 notice/ask/later/emit 动作")
                .inputSchema(Map.of(
                        "type", "object",
                        "required", List.of("outcome", "summary"),
                        "properties", Map.of(
                                "outcome", Map.of("type", "string",
                                        "enum", List.of("DONE", "NOTHING", "FAILED", "CANCELLED")),
                                "summary", Map.of("type", "string", "maxLength", ReceiptLimits.SUMMARY_MAX_LENGTH),
                                "data", Map.of("type", "object"),
                                "actions", Map.of("type", "array"))))
                .risk(ToolRisk.WRITE)
                .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        if (!OrchTools.RECEIPT.equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        if (arguments == null || arguments.get("outcome") == null || arguments.get("summary") == null) {
            return new ToolResult(false, "outcome 与 summary 必填");
        }
        return new ToolResult(true, "receipt recorded; applied after turn finishes");
    }
}
