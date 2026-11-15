package com.tioka.model.api.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-07（ADR-041 §4 / ADR-048 §2.3 / ADR-049）：内置 Prompt 模板集合自检。
 */
class BuiltinPromptTemplatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allBuiltinsPassValidation() {
        List<PromptTemplateValidator.Issue> issues = BuiltinPromptTemplates.validateAll();
        assertTrue(PromptTemplateValidator.ok(issues), "内置模板应全部通过校验: " + issues);
    }

    @Test
    void allBuiltinsAreSystemScopedAndSysPrefixed() {
        List<PromptTemplateSpec> list = BuiltinPromptTemplates.list();
        assertTrue(list.size() >= 5, "至少 5 个内置模板");
        Set<String> names = new HashSet<>();
        for (PromptTemplateSpec s : list) {
            assertEquals(PromptTemplateSpec.SCOPE_SYSTEM, s.scope());
            assertTrue(s.name().startsWith("sys."), "系统级模板须以 sys. 开头: " + s.name());
            assertTrue(names.add(s.name()), "模板名必须唯一: " + s.name());
        }
    }

    @Test
    void idsAreStableAndNamespaced() {
        assertEquals("prmtpl_sys_decider", BuiltinPromptTemplates.idOf("sys.decider"));
        assertEquals("prmtpl_sys_memory_extract", BuiltinPromptTemplates.idOf("sys.memory.extract"));
        assertTrue(BuiltinPromptTemplates.idOf("sys.compaction").startsWith(BuiltinPromptTemplates.ID_PREFIX));
    }

    @Test
    void allMapContainsEveryTemplate() {
        var map = BuiltinPromptTemplates.all();
        assertEquals(BuiltinPromptTemplates.list().size(), map.size());
        assertTrue(map.containsKey("sys.decider"));
    }

    @Test
    void canonicalJsonIsStableForSameTemplate() {
        PromptTemplateSpec s = BuiltinPromptTemplates.summarize();
        assertEquals(BuiltinPromptTemplates.canonicalJson(MAPPER, s),
                BuiltinPromptTemplates.canonicalJson(MAPPER, s));
    }
}
