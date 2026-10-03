/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

/**
 * saiku#1434 — {@code saiku model diff}.
 *
 * <p>The exit code is the whole point of a CI gate, so the tests pin all four: clean, broken,
 * unreadable input, and cross-format.
 */
public class ModelCommandTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SCHEMA = "<Schema name='FoodMart'><Cube name='Sales'>"
            + "<Measure name='Unit Sales' column='us' aggregator='sum'/>"
            + "<Measure name='Store Cost' column='sc' aggregator='sum'/>"
            + "<Dimension name='Product'><Hierarchy><Level name='Product Family' column='pf'/>"
            + "</Hierarchy></Dimension></Cube></Schema>";

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @Before
    public void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int run(String... args) {
        return new CommandLine(new ModelCommand()).execute(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void anUnchangedModelExitsZeroAndReportsNothingBroken() throws Exception {
        Path before = write("before.xml", SCHEMA);
        assertEquals(0, run("diff", "--before", before.toString(), "--after", before.toString()));
        assertTrue(stdout(), stdout().contains("No model changes and no broken references."));
    }

    @Test
    public void aRenameWithAStaleDashboardExitsOneAndNamesTheFile() throws Exception {
        Path before = write("before.xml", SCHEMA);
        Path after = write("after.xml", SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        Path homes = tmp.newFolder("repository", "data", "homes", "admin").toPath();
        Files.writeString(
                homes.resolve("trend.saiku"),
                "{\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Sales]\"}",
                StandardCharsets.UTF_8);
        Path repository = homes.getParent().getParent().getParent();

        assertEquals(
                1,
                run(
                        "diff",
                        "--before",
                        before.toString(),
                        "--after",
                        after.toString(),
                        "--repository",
                        repository.toString()));
        assertTrue(stdout(), stdout().contains("Renamed measure: Store Cost → Cost of Goods"));
        assertTrue(stdout(), stdout().contains("homes/admin/trend.saiku"));
        assertTrue(stdout(), stdout().contains("[Measures].[Store Cost]"));
    }

    @Test
    public void noFailOnBrokenAlwaysExitsZero() throws Exception {
        Path before = write("before.xml", SCHEMA);
        Path after = write("after.xml", SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        Path homes = tmp.newFolder("repo2", "homes", "admin").toPath();
        Files.writeString(
                homes.resolve("trend.saiku"),
                "{\"cube\":{\"name\":\"Sales\"},"
                        + "\"mdx\":\"SELECT {[Measures].[Store Cost]} ON COLUMNS FROM [Sales]\"}",
                StandardCharsets.UTF_8);

        assertEquals(
                0,
                run(
                        "diff",
                        "--before",
                        before.toString(),
                        "--after",
                        after.toString(),
                        "--repository",
                        homes.getParent().toString(),
                        "--no-fail-on-broken"));
    }

    @Test
    public void anOssieModelDiffsJustAsWell() throws Exception {
        String yaml = "semantic_model:\n- name: Flights\n  metrics:\n  - name: flight_count\n";
        Path before = write("before.yaml", yaml);
        Path after = write("after.yaml", yaml.replace("name: flight_count", "name: flights"));
        assertEquals(0, run("diff", "--before", before.toString(), "--after", after.toString()));
        assertTrue(stdout(), stdout().contains("Renamed measure: flight_count → flights"));
    }

    @Test
    public void crossFormatIsRejectedWithItsOwnExitCode() throws Exception {
        Path xml = write("schema.xml", SCHEMA);
        Path yaml = write("model.yaml", "semantic_model:\n- name: F\n  metrics:\n  - name: m\n");
        assertEquals(3, run("diff", "--before", xml.toString(), "--after", yaml.toString()));
        assertTrue(stderr(), stderr().contains("refusing to diff"));
    }

    @Test
    public void malformedInputExitsTwo() throws Exception {
        Path good = write("good.xml", SCHEMA);
        Path bad = write("bad.xml", "<Schema name='X'>");
        assertEquals(2, run("diff", "--before", good.toString(), "--after", bad.toString()));
        assertTrue(stderr(), stderr().contains("not well-formed XML"));
    }

    @Test
    public void aMissingFileExitsTwoWithAPointerAtTheProblem() throws Exception {
        Path good = write("good.xml", SCHEMA);
        assertEquals(2, run("diff", "--before", good.toString(), "--after", "nope.xml"));
        assertTrue(stderr(), stderr().contains("not a readable file"));
    }

    @Test
    public void noBeforeExitsTwo() {
        assertEquals(2, run("diff"));
        assertTrue(stderr(), stderr().contains("--before is required"));
    }

    @Test
    public void jsonOutputIsMachineReadable() throws Exception {
        Path before = write("before.xml", SCHEMA);
        Path after = write("after.xml", SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'"));
        assertEquals(0, run("diff", "--before", before.toString(), "--after", after.toString(), "--json"));
        assertTrue(stdout(), stdout().contains("\"type\": \"RENAMED\""));
        assertTrue(stdout(), stdout().contains("\"breaking\": true"));
    }

    @Test
    public void modelsAreReadOutOfGitRefsRatherThanFromTheWorkingTree() {
        ModelCommand.DiffCommand command = new ModelCommand.DiffCommand();
        // The point under test: with --from-git, the models come out of the ref and the working
        // tree is never read — the path below deliberately does not exist on disk.
        command.tool.setGitBlobReader(new org.saiku.service.schema.diff.ModelDiffTool.GitBlobReader() {
            @Override
            public String show(String ref, java.nio.file.Path path) {
                assertTrue(
                        "path should be passed through verbatim: " + path,
                        path.toString().contains("schema.xml"));
                return "origin/main".equals(ref) ? SCHEMA : SCHEMA.replace("name='Store Cost'", "name='Cost of Goods'");
            }
        });
        int exit = new CommandLine(command)
                .execute(
                        "--from-git",
                        "origin/main",
                        "--from-git",
                        "HEAD",
                        "--before",
                        "data/schema.xml",
                        "--after",
                        "data/schema.xml");
        assertEquals(stderr(), 0, exit);
        assertTrue(stdout(), stdout().contains("Renamed measure: Store Cost → Cost of Goods"));
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.newFile(name).toPath();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
