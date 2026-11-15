package com.tioka.model.api.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST-07（ADR-041 §4 / ADR-048 §2.3、ADR-049）：系统内置 Prompt 模板初始集合。
 *
 * <p>平台随版本发布第 1 版；系统管理员可发布覆盖版本，也可回滚到内置版本。
 * 决策器、胜任度打分、上下文压缩、记忆抽取按 {@code sys.*} 名称跟随最新版本。
 *
 * <p>纯函数：同一名字给出同一 spec；ID 固定（{@code prmtpl_sys_<名>}）保证迁移幂等。
 */
public final class BuiltinPromptTemplates {

    private BuiltinPromptTemplates() {
    }

    /** 内置模板的稳定 ID 前缀。 */
    public static final String ID_PREFIX = "prmtpl_sys_";

    /** 内置模板（名称 → spec，按声明顺序）。 */
    public static Map<String, PromptTemplateSpec> all() {
        Map<String, PromptTemplateSpec> out = new LinkedHashMap<>();
        for (PromptTemplateSpec s : list()) {
            out.put(s.name(), s);
        }
        return out;
    }

    public static List<PromptTemplateSpec> list() {
        List<PromptTemplateSpec> out = new ArrayList<>();
        out.add(decider());
        out.add(competence());
        out.add(summarize());
        out.add(memoryExtract());
        out.add(compaction());
        return out;
    }

    /** 由模板名得到固定 ID（迁移幂等键）。 */
    public static String idOf(String name) {
        return ID_PREFIX + name.substring(name.indexOf('.') + 1).replace('.', '_');
    }

    /** 校验全部内置模板（供测试与迁移自检）。 */
    public static List<PromptTemplateValidator.Issue> validateAll() {
        List<PromptTemplateValidator.Issue> issues = new ArrayList<>();
        for (PromptTemplateSpec s : list()) {
            issues.addAll(PromptTemplateValidator.validate(s));
        }
        return issues;
    }

    /** 模板 spec → 规范化 JSON（迁移写入 cfg_version 用；与 ModelHubSpecs.canonicalJson 同口径）。 */
    public static String canonicalJson(ObjectMapper mapper, PromptTemplateSpec spec) {
        return com.tioka.model.api.versioning.ModelHubSpecs.canonicalJson(mapper, spec.toJson(mapper));
    }

    // ------------------------------------------------------------------ 内置集合

    public static PromptTemplateSpec decider() {
        return new PromptTemplateSpec(
                "sys.decider", PromptTemplateSpec.SCOPE_SYSTEM, null, "decision",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是模型路由决策器。只依据候选清单做选择，不得编造候选。\n决策准则：{{criteria}}"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "任务：{{question}}\n候选清单：\n{{#candidates}}{{.}}\n{{/candidates}}"
                                        + "请给出所选候选编号与理由。")),
                List.of(
                        new PromptTemplateSpec.Variable("question", PromptTemplateSpec.TYPE_STRING, true, null,
                                "需要选择模型的任务描述"),
                        new PromptTemplateSpec.Variable("candidates", PromptTemplateSpec.TYPE_LIST, true, null,
                                "候选清单（每项一行文本）"),
                        new PromptTemplateSpec.Variable("criteria", PromptTemplateSpec.TYPE_STRING, false,
                                "优先准确率，其次成本", "决策准则")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.decision",
                Map.of("question", "把这段合同归类到业务类型",
                        "candidates", List.of("1. gpt-4o", "2. qwen-max"),
                        "criteria", "优先准确率"));
    }

    public static PromptTemplateSpec competence() {
        return new PromptTemplateSpec(
                "sys.competence", PromptTemplateSpec.SCOPE_SYSTEM, null, "decision",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是模型胜任度评分器。对候选模型在指定任务上的表现打 0-100 分并给出理由。"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "任务：{{task}}\n候选模型：{{model}}\n历史表现：\n"
                                        + "{{#history}}{{.}}\n{{/history}}请输出分数与理由。")),
                List.of(
                        new PromptTemplateSpec.Variable("task", PromptTemplateSpec.TYPE_STRING, true, null,
                                "待评估的任务"),
                        new PromptTemplateSpec.Variable("model", PromptTemplateSpec.TYPE_STRING, true, null,
                                "候选模型名"),
                        new PromptTemplateSpec.Variable("history", PromptTemplateSpec.TYPE_LIST, true, null,
                                "历史表现条目（每项一行文本）")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.decision",
                Map.of("task", "合同要素抽取",
                        "model", "gpt-4o",
                        "history", List.of("上次任务 F1=0.92", "平均延迟 1.4s")));
    }

    public static PromptTemplateSpec summarize() {
        return new PromptTemplateSpec(
                "sys.summarize", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是摘要助手，输出不超过 {{maxWords}} 字，保留关键结论与数字。"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "内容：\n{{content}}")),
                List.of(
                        new PromptTemplateSpec.Variable("content", PromptTemplateSpec.TYPE_STRING, true, null,
                                "待摘要的正文"),
                        new PromptTemplateSpec.Variable("maxWords", PromptTemplateSpec.TYPE_NUMBER, false, "200",
                                "摘要字数上限")),
                PromptTemplateSpec.FORMAT_TEXT, null, "sys.chat",
                Map.of("content", "（正文）", "maxWords", "200"));
    }

    public static PromptTemplateSpec memoryExtract() {
        return new PromptTemplateSpec(
                "sys.memory.extract", PromptTemplateSpec.SCOPE_SYSTEM, null, "extract",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是记忆抽取器。从对话中抽取值得长期保留的记忆条目，严格按给定 Schema 输出 JSON。"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "对话：\n{{conversation}}\nSchema 提示：{{schemaHint}}")),
                List.of(
                        new PromptTemplateSpec.Variable("conversation", PromptTemplateSpec.TYPE_STRING, true, null,
                                "待抽取的对话正文"),
                        new PromptTemplateSpec.Variable("schemaHint", PromptTemplateSpec.TYPE_STRING, true, null,
                                "结构化输出 Schema 提示")),
                PromptTemplateSpec.FORMAT_JSON, "schema://memory.extract.v1", "sys.chat",
                Map.of("conversation", "用户：我偏好用邮件通知。助手：已记录。",
                        "schemaHint", "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
    }

    public static PromptTemplateSpec compaction() {
        return new PromptTemplateSpec(
                "sys.compaction", PromptTemplateSpec.SCOPE_SYSTEM, null, "summarize",
                List.of(
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_SYSTEM,
                                "你是上下文压缩器。把历史对话压缩为结构化栏目并输出 JSON，不得丢失待办与约束。"),
                        new PromptTemplateSpec.Section(PromptTemplateSpec.ROLE_USER,
                                "历史：\n{{history}}\n已保留栏目：\n{{#sections}}{{.}}\n{{/sections}}")),
                List.of(
                        new PromptTemplateSpec.Variable("history", PromptTemplateSpec.TYPE_STRING, true, null,
                                "待压缩的历史对话"),
                        new PromptTemplateSpec.Variable("sections", PromptTemplateSpec.TYPE_LIST, true, null,
                                "需要保留的栏目名（每项一行）")),
                PromptTemplateSpec.FORMAT_JSON, "schema://compaction.v1", "sys.chat",
                Map.of("history", "（历史对话）",
                        "sections", List.of("目标", "进展", "待办", "约束")));
    }
}
