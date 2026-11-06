package com.tioka.runtime.api.spi;

import com.tioka.runtime.api.approval.ToolRisk;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ToolProvider {
    String name();
    List<ToolDescriptor> listTools(String agentVersionId);
    ToolResult invoke(String toolName, Map<String, Object> arguments);

    /** 带 AgentVersion 作用域的调用；默认回退无作用域 invoke。 */
    default ToolResult invoke(String agentVersionId, String toolName, Map<String, Object> arguments) {
        return invoke(toolName, arguments);
    }

    /**
     * 工具关联的出网地址（CON-01 第 7 步 EgressGuard）；无外网则空。
     */
    default Optional<String> egressUrl(String agentVersionId, String toolName) {
        return Optional.empty();
    }

    /**
     * MCP 工具所属 serverId（CON-01 授权）；非 MCP 为空。
     */
    default Optional<String> mcpServerId(String agentVersionId, String toolName) {
        return Optional.empty();
    }

    /**
     * 工具描述。用 {@link #builder()} 构造（M1-00 F2）；兼容三参 / 四参构造器。
     */
    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema,
                          ToolRisk risk, String requiredPermission) {
        public ToolDescriptor {
            if (risk == null) {
                risk = ToolRisk.READ;
            }
            if (inputSchema == null) {
                inputSchema = Map.of();
            }
        }

        public ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, ToolRisk.READ, null);
        }

        public ToolDescriptor(String name, String description, Map<String, Object> inputSchema, ToolRisk risk) {
            this(name, description, inputSchema, risk, null);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String name;
            private String description = "";
            private Map<String, Object> inputSchema = Map.of();
            private ToolRisk risk = ToolRisk.READ;
            private String requiredPermission;

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder description(String description) {
                this.description = description == null ? "" : description;
                return this;
            }

            public Builder inputSchema(Map<String, Object> inputSchema) {
                this.inputSchema = inputSchema == null ? Map.of() : inputSchema;
                return this;
            }

            public Builder risk(ToolRisk risk) {
                this.risk = risk == null ? ToolRisk.READ : risk;
                return this;
            }

            public Builder requiredPermission(String requiredPermission) {
                this.requiredPermission = requiredPermission;
                return this;
            }

            public ToolDescriptor build() {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("tool name required");
                }
                return new ToolDescriptor(name, description, inputSchema, risk, requiredPermission);
            }
        }
    }

    record ToolResult(boolean ok, String content) {}
}
