/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1434 — {@link ModelDiffTool}, the entry point both the {@code saiku model diff}
 * subcommand and the {@code model-diff} GitHub workflow run.
 *
 * <p>The workflow invokes {@link ModelDiffTool#main} directly against compiled
 * {@code saiku-service} classes, so argv parsing and the exit codes it produces are part of the
 * contract, not an implementation detail.
 */
class ModelDiffToolTest {

    @TempDir
    Path work;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream stdout;
    private PrintStream stderr;

    private static final String SCHEMA = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='us' aggregator='sum'/>"
            + "<Measure name='Store Cost' column='sc' aggregator='sum'/></Cube></Schema>";

    @BeforeEach
    void setUp() {
        stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
        stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    private ModelDiffTool.Result run(ModelDiffTool.Options options) {
        return new ModelDiffTool().run(options, stdout, stderr);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, String content) throws IOException {
        Path file = work.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private ModelDiffTool.Options options(Path before, Path after) {
        ModelDiffTool.Options options = new ModelDiffTool.Options();
        options.before = before;
        options.after = after;
        return options;
    }

    @Test
    void aCleanDiffExitsZero() throws IOException {
        Path schema = write("a/schema.xml", SCHEMA);
        ModelDiffTool.Result result = run(options(schema, schema));
        assertEquals(0, result.exitCode());
        assertTrue(result.report().isClean());
        assertTrue(out().contains("No model changes and no broken references."), out());
    }

    @Test
    void brokenReferencesExitOneUnlessSuppressed() throws IOException {
        Path before = write("a/schema.xml", SCHEMA);
        Path after = write("a/after.xml", SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        Path home = work.resolve("repo/homes/admin");
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("q.saiku"),
                "{\"cube\":{\"name\":\"Sales\"},\"mdx\":\"SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Sales]\"}");

        ModelDiffTool.Options failing = options(before, after);
        failing.repository = work.resolve("repo");
        assertEquals(1, run(failing).exitCode());

        out.reset();
        ModelDiffTool.Options lenient = options(before, after);
        lenient.repository = work.resolve("repo");
        lenient.failOnBroken = false;
        assertEquals(0, run(lenient).exitCode());
        assertTrue(out().contains("homes/admin/q.saiku"), out());
    }

    @Test
    void crossFormatExitsThree() throws IOException {
        Path xml = write("a/schema.xml", SCHEMA);
        Path yaml = write("a/model.yaml", "semantic_model:\n- name: F\n  metrics:\n  - name: m\n");
        assertEquals(3, run(options(xml, yaml)).exitCode());
        assertTrue(err().contains("refusing to diff"), err());
    }

    @Test
    void aMissingBeforeIsTwo() {
        assertEquals(2, run(options(null, null)).exitCode());
        assertTrue(err().contains("--before is required"), err());
    }

    @Test
    void anUnreadableFileIsTwo() throws IOException {
        Path before = write("a/schema.xml", SCHEMA);
        assertEquals(2, run(options(before, work.resolve("nope.xml"))).exitCode());
        assertTrue(err().contains("not a readable file"), err());
    }

    @Test
    void jsonOutputCarriesTheMachineReadableFields() throws IOException {
        Path before = write("a/schema.xml", SCHEMA);
        Path after = write("a/after.xml", SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        ModelDiffTool.Options json = options(before, after);
        json.json = true;
        assertEquals(0, run(json).exitCode());
        assertTrue(out().contains("\"format\": \"MONDRIAN_XML\""), out());
        assertTrue(out().contains("\"type\": \"RENAMED\""), out());
        assertTrue(out().contains("\"to\": \"Cost of Goods\""), out());
    }

    @Test
    void gitRefsAreUsedInsteadOfTheWorkingTree() throws IOException {
        ModelDiffTool tool = new ModelDiffTool();
        List<String> requestedRefs = new ArrayList<>();
        tool.setGitBlobReader((ref, path) -> {
            requestedRefs.add(ref + ":" + path);
            return "base".equals(ref) ? SCHEMA : SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'");
        });
        ModelDiffTool.Options options = new ModelDiffTool.Options();
        options.before = Path.of("data/FoodMart4.xml");
        options.after = Path.of("data/FoodMart4.xml");
        options.fromGit = List.of("base", "head");

        ModelDiffTool.Result result = tool.run(options, stdout, stderr);
        assertEquals(0, result.exitCode());
        assertEquals(List.of("base:data/FoodMart4.xml", "head:data/FoodMart4.xml"), requestedRefs);
        assertTrue(out().contains("Renamed measure: Store Cost → Cost of Goods"), out());
    }

    @Test
    void argvParsingCoversTheDocumentedFlags() {
        // Parsed here rather than through main(), which would System.exit.
        List<String> args = new ArrayList<>(List.of(
                "--before",
                "a.xml",
                "--after",
                "b.yaml",
                "--from-git",
                "base",
                "--from-git",
                "head",
                "--repository",
                "repo",
                "--json",
                "--no-fail-on-broken"));
        ModelDiffTool.Options options = new ModelDiffTool.Options();
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--before" -> options.before = Path.of(args.get(++i));
                case "--after" -> options.after = Path.of(args.get(++i));
                case "--repository" -> options.repository = Path.of(args.get(++i));
                case "--from-git" -> refs.add(args.get(++i));
                case "--json" -> options.json = true;
                case "--no-fail-on-broken" -> options.failOnBroken = false;
                default -> throw new AssertionError("unhandled flag " + args.get(i));
            }
        }
        options.fromGit = refs;
        assertEquals(Path.of("a.xml"), options.before);
        assertEquals(Path.of("b.yaml"), options.after);
        assertEquals(Path.of("repo"), options.repository);
        assertEquals("base", options.refAt(0));
        assertEquals("head", options.refAt(1));
        assertTrue(options.json);
        assertEquals(false, options.failOnBroken);
    }
}
