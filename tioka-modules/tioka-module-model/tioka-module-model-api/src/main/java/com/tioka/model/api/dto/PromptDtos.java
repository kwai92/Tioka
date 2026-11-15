package com.tioka.model.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** AST-07（ADR-041 §4）：Prompt 模板资产 DTO。 */
public final class PromptDtos {

    private PromptDtos() {
    }

    /** 创建模板身份（内容由 spec 提供；publish=true 时登记并发布第 1 版）。 */
    public record CreatePromptTemplateRequest(JsonNode spec, Boolean publish) {
    }

    /** 保存草稿（expectedRevision 乐观锁；首存传 0）。 */
    public record SavePromptDraftRequest(JsonNode spec, Long expectedRevision) {
    }

    public record PublishPromptRequest(String note) {
    }

    public record RollbackPromptRequest(Integer targetVersionNo, String reason) {
    }

    /** 渲染请求（无模型调用）。 */
    public record RenderPromptRequest(Map<String, Object> variables) {
    }

    /** 试运行请求（渲染 + 模型调用）；{@code aliasName} 省略时用 spec.suggestedAlias。 */
    public record TestRunPromptRequest(String aliasName, String userInput, Map<String, Object> variables) {
    }

    /** 模板视图（身份 + 当前生效 spec；无版本时 spec 为 null）。 */
    public record PromptTemplateView(
            String id, String name, String scope, String scopeRef,
            Integer currentVersionNo, String status, JsonNode spec) {
    }

    public record RenderedSectionView(String role, String body) {
    }

    /** 渲染结果（无模型调用）。 */
    public record RenderResultView(String name, List<RenderedSectionView> sections) {
    }

    /** 试运行结果（渲染 + 模型回答）。 */
    public record TestRunResultView(
            String name, List<RenderedSectionView> sections,
            String bindingId, String modelRef, String content,
            int promptTokens, int completionTokens) {
    }
}
