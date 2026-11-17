package com.tioka.model.api.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AST-07（ADR-041 §4 / §2 校验）：Prompt 模板的发布期校验（纯函数）。
 *
 * <p>除字段合法性外，核心两条：正文里的占位符与变量定义一致（双向），并能用样例变量渲染成功。
 * 由 model-biz 的 {@code ConfigSpecValidator} 适配到 GOV-12 发布校验。
 */
public final class PromptTemplateValidator {

    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARN = "WARN";

    private static final Set<String> SCOPES = Set.of(
            PromptTemplateSpec.SCOPE_SYSTEM, PromptTemplateSpec.SCOPE_PACK,
            PromptTemplateSpec.SCOPE_WORKSPACE, PromptTemplateSpec.SCOPE_PERSONAL);
    private static final Set<String> TYPES = Set.of(
            PromptTemplateSpec.TYPE_STRING, PromptTemplateSpec.TYPE_NUMBER,
            PromptTemplateSpec.TYPE_BOOLEAN, PromptTemplateSpec.TYPE_LIST, PromptTemplateSpec.TYPE_OBJECT);

    private PromptTemplateValidator() {
    }

    /** 校验问题。 */
    public record Issue(String code, String severity, String message, String path) {}

    public static List<Issue> validate(PromptTemplateSpec spec) {
        List<Issue> issues = new ArrayList<>();
        if (spec == null) {
            issues.add(new Issue("PROMPT_SPEC_NULL", SEVERITY_ERROR, "模板 spec 为空", "/"));
            return List.copyOf(issues);
        }
        checkName(spec, issues);
        checkPurpose(spec, issues);
        checkSections(spec, issues);
        checkFormat(spec, issues);
        checkVariables(spec, issues);
        checkPlaceholders(spec, issues);
        return List.copyOf(issues);
    }

    /** 是否无 ERROR（WARN 不阻断发布）。 */
    public static boolean ok(List<Issue> issues) {
        return issues.stream().noneMatch(i -> SEVERITY_ERROR.equals(i.severity()));
    }

    private static void checkName(PromptTemplateSpec spec, List<Issue> issues) {
        String name = spec.name();
        if (name == null || name.isBlank()) {
            issues.add(new Issue("PROMPT_NAME_REQUIRED", SEVERITY_ERROR, "模板名称必填", "/name"));
            return;
        }
        String scope = PromptTemplateSpec.normalizeScope(spec.scope());
        if (scope == null || !SCOPES.contains(scope)) {
            issues.add(new Issue("PROMPT_SCOPE_INVALID", SEVERITY_ERROR,
                    "作用域必须是 system/pack/workspace/personal: " + spec.scope(), "/scope"));
            return;
        }
        if (PromptTemplateSpec.SCOPE_SYSTEM.equals(scope) && !name.startsWith("sys.")) {
            issues.add(new Issue("PROMPT_NAME_SCOPE_MISMATCH", SEVERITY_ERROR,
                    "系统级模板名称须以 sys. 开头: " + name, "/name"));
        }
        if (PromptTemplateSpec.SCOPE_PACK.equals(scope) && !name.startsWith("pack.")) {
            issues.add(new Issue("PROMPT_NAME_SCOPE_MISMATCH", SEVERITY_ERROR,
                    "领域包模板名称须以 pack. 开头: " + name, "/name"));
        }
    }

    private static void checkPurpose(PromptTemplateSpec spec, List<Issue> issues) {
        String purpose = spec.purpose();
        if (purpose == null || purpose.isBlank()
                || !PromptTemplateSpec.PURPOSES.contains(purpose.toLowerCase(Locale.ROOT))) {
            issues.add(new Issue("PROMPT_PURPOSE_INVALID", SEVERITY_ERROR,
                    "用途必须是 " + PromptTemplateSpec.PURPOSES + " 之一: " + purpose, "/purpose"));
        }
    }

    private static void checkSections(PromptTemplateSpec spec, List<Issue> issues) {
        List<PromptTemplateSpec.Section> sections = spec.sections();
        if (sections == null || sections.isEmpty()) {
            issues.add(new Issue("PROMPT_SECTION_EMPTY", SEVERITY_ERROR, "至少需要一个分段", "/sections"));
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            PromptTemplateSpec.Section s = sections.get(i);
            String path = "/sections/" + i;
            if (s.role() == null
                    || !(PromptTemplateSpec.ROLE_SYSTEM.equals(s.role()) || PromptTemplateSpec.ROLE_USER.equals(s.role()))) {
                issues.add(new Issue("PROMPT_ROLE_INVALID", SEVERITY_ERROR,
                        "分段角色必须是 system 或 user: " + s.role(), path + "/role"));
            }
            if (s.body() == null || s.body().isBlank()) {
                issues.add(new Issue("PROMPT_BODY_EMPTY", SEVERITY_ERROR, "分段正文不能为空", path + "/body"));
            }
        }
    }

    private static void checkFormat(PromptTemplateSpec spec, List<Issue> issues) {
        String fmt = spec.outputFormat() == null ? PromptTemplateSpec.FORMAT_TEXT : spec.outputFormat();
        if (!PromptTemplateSpec.FORMAT_TEXT.equals(fmt) && !PromptTemplateSpec.FORMAT_JSON.equals(fmt)) {
            issues.add(new Issue("PROMPT_FORMAT_INVALID", SEVERITY_ERROR,
                    "输出格式必须是 text 或 json: " + fmt, "/outputFormat"));
            return;
        }
        if (PromptTemplateSpec.FORMAT_JSON.equals(fmt)
                && (spec.schemaRef() == null || spec.schemaRef().isBlank())) {
            issues.add(new Issue("PROMPT_SCHEMA_MISSING", SEVERITY_WARN,
                    "输出格式为 json 时建议引用结构化输出 Schema（ADR-037）", "/schemaRef"));
        }
    }

    private static void checkVariables(PromptTemplateSpec spec, List<Issue> issues) {
        List<PromptTemplateSpec.Variable> variables = spec.variables();
        if (variables == null) {
            return;
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < variables.size(); i++) {
            PromptTemplateSpec.Variable v = variables.get(i);
            String path = "/variables/" + i;
            if (v.name() == null || v.name().isBlank()) {
                issues.add(new Issue("PROMPT_VARIABLE_NAME_REQUIRED", SEVERITY_ERROR, "变量名必填", path + "/name"));
                continue;
            }
            if (!seen.add(v.name())) {
                issues.add(new Issue("PROMPT_VARIABLE_DUPLICATE", SEVERITY_ERROR,
                        "变量名重复: " + v.name(), path + "/name"));
            }
            if (v.type() == null || !TYPES.contains(v.type())) {
                issues.add(new Issue("PROMPT_VARIABLE_TYPE_INVALID", SEVERITY_ERROR,
                        "变量类型必须是 " + TYPES + " 之一: " + v.type(), path + "/type"));
            }
        }
    }

    private static void checkPlaceholders(PromptTemplateSpec spec, List<Issue> issues) {
        Set<String> placeholders = spec.placeholders();
        Set<String> declared = spec.declaredNames();
        for (String p : placeholders) {
            if (!declared.contains(p)) {
                issues.add(new Issue("PROMPT_PLACEHOLDER_UNDEFINED", SEVERITY_ERROR,
                        "正文占位符未在变量定义中声明: " + p, "/sections"));
            }
        }
        for (String d : declared) {
            if (!placeholders.contains(d)) {
                issues.add(new Issue("PROMPT_VARIABLE_UNUSED", SEVERITY_ERROR,
                        "变量定义未被正文引用: " + d, "/variables"));
            }
        }
        // 样例变量渲染（发布前自检）
        try {
            PromptRenderEngine.render(spec, spec.sampleVariables());
        } catch (PromptRenderException e) {
            issues.add(new Issue("PROMPT_SAMPLE_RENDER_FAILED", SEVERITY_ERROR,
                    "样例变量渲染失败: " + e.reason(), "/sampleVariables"));
        }
    }
}
