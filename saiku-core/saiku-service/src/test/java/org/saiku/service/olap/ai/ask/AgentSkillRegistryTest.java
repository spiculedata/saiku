/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Registry tests: scanning, deduplication, mtime-based refresh, and structured error surfacing.
 */
public class AgentSkillRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emptyRootYieldsEmptyCatalogue() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmptyCatalogue() {
        AgentSkillRegistry reg = new AgentSkillRegistry(tmp.getRoot().toPath().resolve("does-not-exist"));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void scansMarkdownFiles() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("a.md"), "---\nname: skill-a\ndescription: A\n---\n\nbody-a\n");
        write(root.resolve("b.md"), "---\nname: skill-b\ndescription: B\n---\n\nbody-b\n");
        // Non-.md files must be ignored — a stray README shouldn't crash the scan.
        write(root.resolve("README.txt"), "irrelevant");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        List<AgentSkill> skills = reg.list();
        assertEquals(2, skills.size());
        assertEquals("skill-a", skills.get(0).name());
        assertEquals("skill-b", skills.get(1).name());
    }

    @Test
    public void surfacesErrorsForBadFiles() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("ok.md"), "---\nname: ok\ndescription: fine\n---\n\nbody\n");
        write(root.resolve("bad.md"), "no frontmatter here\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        // Good file still loads.
        assertEquals(1, reg.list().size());
        assertEquals("ok", reg.list().get(0).name());
        // Bad file surfaces a structured error.
        assertEquals(1, reg.errors().size());
        assertEquals("MISSING_FRONTMATTER", reg.errors().get(0).code());
        assertTrue(reg.errors().get(0).path().contains("bad.md"));
    }

    @Test
    public void dedupesOnName() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("first.md"), "---\nname: dup\ndescription: first\n---\n\nbody\n");
        Path sub = Files.createDirectory(root.resolve("nested"));
        write(sub.resolve("second.md"), "---\nname: dup\ndescription: second\n---\n\nbody\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_NAME", reg.errors().get(0).code());
    }

    @Test
    public void refreshesOnFileChange() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        Path a = root.resolve("a.md");
        write(a, "---\nname: a\ndescription: v1\n---\n\nbody\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        assertEquals("v1", reg.list().get(0).description());

        // Edit content + bump mtime. On some filesystems mtime granularity is 1s;
        // set it explicitly to a value clearly ahead of "now" so the signature changes.
        write(a, "---\nname: a\ndescription: v2\n---\n\nbody\n");
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertEquals("v2", reg.list().get(0).description());
    }

    @Test
    public void detectsAddedFileWithoutForceRefresh() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("a.md"), "---\nname: a\ndescription: A\n---\n\nbody\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        assertEquals(1, reg.list().size());
        write(root.resolve("b.md"), "---\nname: b\ndescription: B\n---\n\nbody\n");
        assertEquals(2, reg.list().size());
    }

    @Test
    public void getByName() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("a.md"), "---\nname: skill-a\ndescription: A\n---\n\nbody\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        assertTrue(reg.get("skill-a").isPresent());
        assertFalse(reg.get("nope").isPresent());
        assertFalse(reg.get("").isPresent());
        assertFalse(reg.get(null).isPresent());
    }

    @Test
    public void stringPathConstructor() {
        AgentSkillRegistry reg =
                new AgentSkillRegistry(tmp.getRoot().toPath().resolve("skills").toString());
        assertNotNull(reg);
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void promptFragmentContainsAllSkills() throws Exception {
        Path root = tmp.newFolder("skills").toPath();
        write(root.resolve("a.md"), "---\nname: skill-a\ndescription: First skill\n---\n\nbody\n");
        write(root.resolve("b.md"), "---\nname: skill-b\ndescription: Second skill\ncube: c/c/s/Cube\n---\n\nbody\n");
        AgentSkillRegistry reg = new AgentSkillRegistry(root);
        String prompt = AgentSkill.catalogPromptFragment(reg.list());
        assertTrue(prompt.contains("/skill-a"));
        assertTrue(prompt.contains("/skill-b"));
        assertTrue(prompt.contains("First skill"));
        assertTrue(prompt.contains("[cube: c/c/s/Cube]"));
    }

    @Test
    public void emptyCatalogueYieldsEmptyPromptFragment() {
        assertEquals("", AgentSkill.catalogPromptFragment(List.of()));
        assertEquals("", AgentSkill.catalogPromptFragment(null));
    }

    // ---------- helpers ----------

    private static void write(Path p, String content) throws Exception {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
