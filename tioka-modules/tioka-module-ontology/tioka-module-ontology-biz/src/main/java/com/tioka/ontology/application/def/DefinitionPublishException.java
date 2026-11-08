package com.tioka.ontology.application.def;

import java.util.List;

/** 模型定义发布校验失败。 */
public class DefinitionPublishException extends RuntimeException {

    private final List<DefinitionPublishIssue> issues;

    public DefinitionPublishException(List<DefinitionPublishIssue> issues) {
        super(format(issues));
        this.issues = List.copyOf(issues);
    }

    public List<DefinitionPublishIssue> issues() {
        return issues;
    }

    private static String format(List<DefinitionPublishIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "definition publish failed";
        }
        return issues.get(0).code() + ": " + issues.get(0).message();
    }
}
