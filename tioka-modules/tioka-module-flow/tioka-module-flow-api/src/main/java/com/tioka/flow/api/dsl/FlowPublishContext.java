package com.tioka.flow.api.dsl;

import java.util.Set;

/**
 * 发布校验所需的外部解析钩子；FLW-10 仅契约挂钩，真实解析器后续工作项接入。
 */
public interface FlowPublishContext {

    /** 发布形态：FLOW / TOOL / MCP 等；缺省 FLOW。 */
    default String publicationKind() {
        return "FLOW";
    }

    /** 工具同步最长秒数（TOOL/MCP）；缺省 60。 */
    default long toolSyncMaxSeconds() {
        return 60L;
    }

    boolean modelAliasExists(String alias, String purpose);

    boolean promptTemplatePublished(String ref);

    boolean resourceExists(String kind, String ref);

    boolean publisherCanUse(String kind, String ref);

    /** 子流程钉版本展开后的引用图边（from → to），用于环检测。 */
    default Set<String> subflowEdges() {
        return Set.of();
    }

    static FlowPublishContext permissive() {
        return new FlowPublishContext() {
            @Override
            public boolean modelAliasExists(String alias, String purpose) {
                return true;
            }

            @Override
            public boolean promptTemplatePublished(String ref) {
                return true;
            }

            @Override
            public boolean resourceExists(String kind, String ref) {
                return true;
            }

            @Override
            public boolean publisherCanUse(String kind, String ref) {
                return true;
            }
        };
    }
}
