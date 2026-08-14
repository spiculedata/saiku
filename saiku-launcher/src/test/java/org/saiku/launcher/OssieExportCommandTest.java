/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

/**
 * saiku#1808 — {@code ossie-export}'s exit code must distinguish "converted something" from
 * "converted nothing".
 *
 * <p>The documented usage is a pipeline, where stderr is routinely discarded and the exit status
 * is the only signal the caller reads. Because every MDX schema Saiku ships uses the Mondrian 4
 * MeasureGroup shape the converter skips, the common case was an empty {@code semantic_model: []}
 * on stdout, an explanation on stderr nobody sees, and exit 0 — so a build step consuming the YAML
 * carried on with a model that had no datasets and no metrics.
 */
public class OssieExportCommandTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Classic-3: measures hang directly off the cube. The converter handles this. */
    private static final String CLASSIC_3 = "<Schema name='T'><Cube name='C'>"
            + "<Table name='f'/>"
            + "<Measure name='M' column='c' aggregator='sum'/>"
            + "</Cube></Schema>";

    /** Mondrian 4: measures live in a MeasureGroup. The converter skips the cube. */
    private static final String MONDRIAN_4 = "<Schema name='M4' metamodelVersion='4.0'>"
            + "<PhysicalSchema><Table name='f'/></PhysicalSchema>"
            + "<Cube name='Rx'>"
            + "  <MeasureGroups><MeasureGroup name='Rx' table='f'>"
            + "    <Measures><Measure name='M' column='c' aggregator='sum'/></Measures>"
            + "  </MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";

    private int run(String schemaXml, Path out) throws Exception {
        Path in = tmp.newFile().toPath();
        Files.writeString(in, schemaXml, StandardCharsets.UTF_8);
        return new CommandLine(new OssieExportCommand()).execute("--in", in.toString(), "--out", out.toString());
    }

    @Test
    public void convertingNothingExitsNonZero() throws Exception {
        Path out = tmp.newFile().toPath();
        assertEquals("an all-skipped export must not report success", 4, run(MONDRIAN_4, out));
    }

    @Test
    public void convertingNothingStillWritesAValidEmptyModel() throws Exception {
        // The non-zero exit is the signal; the file itself must still be well-formed rather than
        // truncated or absent, so a caller that ignores the code gets something parseable.
        Path out = tmp.newFile().toPath();
        run(MONDRIAN_4, out);
        assertTrue(Files.readString(out).contains("semantic_model: []"));
    }

    @Test
    public void convertingSomethingExitsZero() throws Exception {
        Path out = tmp.newFile().toPath();
        assertEquals(0, run(CLASSIC_3, out));
        String yaml = Files.readString(out);
        assertTrue("expected a populated model — got:\n" + yaml, yaml.contains("name: C"));
    }

    @Test
    public void unreadableInputExitsTwo() throws Exception {
        int code = new CommandLine(new OssieExportCommand())
                .execute("--in", tmp.getRoot().toPath().resolve("nope.xml").toString());
        assertEquals(2, code);
    }
}
