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

public class AgentSpaceRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String GOLDEN =
            "{\"id\":\"%s\",\"name\":\"%s\",\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"c\",\"schema\":\"s\",\"cubeName\":\"C\"}]}";

    @Test
    public void emptyRootYieldsEmpty() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmpty() {
        AgentSpaceRegistry reg = new AgentSpaceRegistry(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void scansJsonFiles() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("a.json"), String.format(GOLDEN, "space-a", "A"));
        write(root.resolve("b.json"), String.format(GOLDEN, "space-b", "B"));
        write(root.resolve("README.txt"), "ignore me");
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        List<AgentSpace> spaces = reg.list();
        assertEquals(2, spaces.size());
        assertEquals("space-a", spaces.get(0).id());
        assertEquals("space-b", spaces.get(1).id());
    }

    @Test
    public void surfacesErrorsForBadFiles() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("ok.json"), String.format(GOLDEN, "ok", "OK"));
        write(root.resolve("bad.json"), "not json at all");
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("MALFORMED_JSON", reg.errors().get(0).code());
    }

    @Test
    public void dedupesOnId() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("first.json"), String.format(GOLDEN, "dup", "First"));
        Path sub = Files.createDirectory(root.resolve("nested"));
        write(sub.resolve("second.json"), String.format(GOLDEN, "dup", "Second"));
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertEquals("DUPLICATE_ID", reg.errors().get(0).code());
    }

    @Test
    public void detectsAddedFile() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("a.json"), String.format(GOLDEN, "a", "A"));
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertEquals(1, reg.list().size());
        write(root.resolve("b.json"), String.format(GOLDEN, "b", "B"));
        assertEquals(2, reg.list().size());
    }

    @Test
    public void getById() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("a.json"), String.format(GOLDEN, "space-a", "A"));
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertTrue(reg.get("space-a").isPresent());
        assertFalse(reg.get("nope").isPresent());
        assertFalse(reg.get("").isPresent());
        assertFalse(reg.get(null).isPresent());
    }

    @Test
    public void stringPathCtor() {
        AgentSpaceRegistry reg =
                new AgentSpaceRegistry(tmp.getRoot().toPath().resolve("spaces").toString());
        assertNotNull(reg);
        assertTrue(reg.list().isEmpty());
    }

    /* ---- saiku#1440: admin CRUD (save / delete / id validation) ---- */

    @Test
    public void saveWritesFileAndRoundTrips() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        AgentSpace s = new AgentSpace(
                "my-space",
                "My Space",
                "desc",
                "You are a test persona.",
                List.of(new org.saiku.service.olap.ai.AiCubeRef("c", "cat", "sch", "Cube")),
                List.of("skill-a"),
                List.of("try this"),
                "my-space.json");
        reg.save(s);

        assertTrue(Files.exists(root.resolve("my-space.json")));
        List<AgentSpace> spaces = reg.list();
        assertEquals(1, spaces.size());
        AgentSpace back = spaces.get(0);
        assertEquals("My Space", back.name());
        assertEquals("You are a test persona.", back.systemPrompt());
        assertEquals(1, back.cubeAllowlist().size());
        assertEquals("Cube", back.cubeAllowlist().get(0).getCubeName());
        assertEquals(List.of("skill-a"), back.skillAllowlist());
        assertEquals(List.of("try this"), back.suggestedPrompts());
    }

    @Test
    public void deleteRemovesFileAndRescans() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        write(root.resolve("gone.json"), String.format(GOLDEN, "gone", "Gone"));
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        assertEquals(1, reg.list().size());
        assertTrue(reg.delete("gone"));
        assertFalse(Files.exists(root.resolve("gone.json")));
        assertTrue(reg.list().isEmpty());
        assertFalse("deleting a missing space returns false", reg.delete("gone"));
    }

    @Test
    public void saveRejectsUnsafeId() throws Exception {
        Path root = tmp.newFolder("spaces").toPath();
        AgentSpaceRegistry reg = new AgentSpaceRegistry(root);
        AgentSpace evil = new AgentSpace("../evil", "x", null, null, List.of(), List.of(), List.of(), "x.json");
        try {
            reg.save(evil);
            org.junit.Assert.fail("a traversal id must be rejected, not written");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void idValidationIsKebabOnly() {
        assertTrue(AgentSpaceRegistry.isValidId("foodmart-sales-analyst"));
        assertFalse(AgentSpaceRegistry.isValidId("../x"));
        assertFalse(AgentSpaceRegistry.isValidId("Foo Bar"));
        assertFalse(AgentSpaceRegistry.isValidId("a/b"));
        assertFalse(AgentSpaceRegistry.isValidId(""));
        assertFalse(AgentSpaceRegistry.isValidId(null));
    }

    private static void write(Path p, String content) throws Exception {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
