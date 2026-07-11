/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EvalSuiteRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CUBE_BLOCK =
            "cube:\n  connectionName: c\n  catalog: cat\n  schema: s\n  cubeName: Sales\n";

    private static String suiteYaml(String name) {
        return "name: " + name + "\n" + CUBE_BLOCK + "cases:\n  - name: a\n    question: q\n";
    }

    @Test
    public void emptyRootYieldsEmpty() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertTrue(reg.list().isEmpty());
        assertTrue(reg.errors().isEmpty());
    }

    @Test
    public void missingRootYieldsEmpty() {
        EvalSuiteRegistry reg = new EvalSuiteRegistry(tmp.getRoot().toPath().resolve("nope"));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    public void scansYamlAndYmlFiles() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        write(root.resolve("a.yaml"), suiteYaml("suite-a"));
        write(root.resolve("b.yml"), suiteYaml("suite-b"));
        write(root.resolve("README.txt"), "irrelevant");
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertEquals(2, reg.list().size());
    }

    @Test
    public void surfacesErrorsForBadFiles() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        write(root.resolve("ok.yaml"), suiteYaml("ok"));
        write(root.resolve("bad.yaml"), "not: valid: yaml: at: all: :\n  broken");
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertEquals(1, reg.list().size());
        assertEquals(1, reg.errors().size());
        assertNotNull(reg.errors().get(0).code());
    }

    @Test
    public void dedupesOnSuiteName() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        write(root.resolve("first.yaml"), suiteYaml("dup"));
        write(root.resolve("second.yaml"), suiteYaml("dup"));
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertEquals(1, reg.list().size());
        assertTrue(reg.errors().stream().anyMatch(e -> "DUPLICATE_NAME".equals(e.code())));
    }

    @Test
    public void refreshesOnFileAdd() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        write(root.resolve("a.yaml"), suiteYaml("a"));
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertEquals(1, reg.list().size());
        write(root.resolve("b.yaml"), suiteYaml("b"));
        assertEquals(2, reg.list().size());
    }

    @Test
    public void getByName() throws Exception {
        Path root = tmp.newFolder("evals").toPath();
        write(root.resolve("a.yaml"), suiteYaml("suite-a"));
        EvalSuiteRegistry reg = new EvalSuiteRegistry(root);
        assertTrue(reg.get("suite-a").isPresent());
        assertFalse(reg.get("nope").isPresent());
        assertFalse(reg.get("").isPresent());
        assertFalse(reg.get(null).isPresent());
    }

    @Test
    public void stringPathCtor() {
        EvalSuiteRegistry reg =
                new EvalSuiteRegistry(tmp.getRoot().toPath().resolve("evals").toString());
        assertNotNull(reg);
        assertTrue(reg.list().isEmpty());
    }

    private static void write(Path p, String content) throws Exception {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
