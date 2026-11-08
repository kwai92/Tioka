package com.tioka.flow.api.dsl;

import java.util.List;

public record ValidationResult(List<ValidationIssue> issues) {

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationSeverity.ERROR);
    }

    public boolean hasCode(String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationSeverity.ERROR).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ValidationSeverity.WARNING).toList();
    }
}
