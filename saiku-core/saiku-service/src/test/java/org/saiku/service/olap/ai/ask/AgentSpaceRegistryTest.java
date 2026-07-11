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

    private static void write(Path p, String content) throws Exception {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
