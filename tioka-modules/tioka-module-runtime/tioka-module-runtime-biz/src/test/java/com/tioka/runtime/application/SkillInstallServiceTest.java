package com.tioka.runtime.application;

import com.tioka.runtime.infrastructure.LocalObjectStore;
import com.tioka.runtime.infrastructure.LocalWorkspaceFs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillInstallServiceTest {
    @TempDir Path tmp;

    @Test
    void installsSkillAndBuildsAutoIndexWithoutFullBody() {
        LocalWorkspaceFs fs = LocalWorkspaceFs.forTest(tmp, new LocalObjectStore(tmp.resolve("o").toString()));
        fs.ensureLayout("ws", "sess");
        SkillInstallService svc = new SkillInstallService(fs);
        var skills = List.of(new SkillInstallService.SkillSnapshot(
                "sk1", "pptx", "auto", "Make PPT", "# Full Skill Body Secret"));
        svc.install("ws", "sess", skills);
        String md = new String(fs.readBytes("ws", "sess", "skills/pptx/SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("Full Skill Body"));
        List<String> frags = svc.promptFragments(skills);
        assertEquals(1, frags.size());
        assertTrue(frags.get(0).contains("pptx"));
        assertFalse(frags.get(0).contains("Full Skill Body Secret"));
    }

    @Test
    void alwaysModeInjectsBody() {
        SkillInstallService svc = new SkillInstallService(
                LocalWorkspaceFs.forTest(tmp, new LocalObjectStore(tmp.resolve("o").toString())));
        var skills = List.of(new SkillInstallService.SkillSnapshot(
                "sk1", "core", "always", "desc", "ALWAYS_BODY"));
        List<String> frags = svc.promptFragments(skills);
        assertTrue(frags.get(0).contains("[skill:core]"));
        assertTrue(frags.get(0).contains("ALWAYS_BODY"));
    }
}
