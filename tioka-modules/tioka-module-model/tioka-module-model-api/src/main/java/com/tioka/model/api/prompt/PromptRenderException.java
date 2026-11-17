package com.tioka.model.api.prompt;

/**
 * AST-07（ADR-041 §4）：无逻辑模板渲染失败。
 *
 * <p>运行期缺少必填变量或模板结构损坏时抛出，由调用方映射为错误码 {@code PROMPT_RENDER_ERROR}。
 */
public class PromptRenderException extends RuntimeException {

    private final String templateName;
    private final String reason;

    public PromptRenderException(String templateName, String reason) {
        super("Prompt 渲染失败[" + templateName + "]: " + reason);
        this.templateName = templateName;
        this.reason = reason;
    }

    public String templateName() {
        return templateName;
    }

    public String reason() {
        return reason;
    }
}
