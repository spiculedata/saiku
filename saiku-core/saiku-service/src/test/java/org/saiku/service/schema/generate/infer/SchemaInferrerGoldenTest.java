package org.saiku.service.schema.generate.infer;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.writer.DraftSchemaJson;

/**
 * Golden-file coverage for the rule-based inferrer. For each fixture JSON under
 * {@code src/test/resources/schemagen/fixtures/}, the test loads it into a {@link DbModel},
 * runs {@link SchemaInferrer}, serialises the resulting draft via {@link DraftSchemaJson}
 * and compares the output to a sibling {@code .expected.json} file.
 *
 * <p><b>Regenerating expected files.</b> When the inferrer output genuinely changes (or when
 * authoring a new fixture), run the tests with
 *
 * <pre>{@code
 * -Dschemagen.updateGolden=true
 * }</pre>
 *
 * <p>This writes the current actual output to each {@code .expected.json}, then fails so the
 * developer inspects the diff and commits only once the output has been eyeballed. Never ship
 * an expected file you haven't reviewed — golden tests only earn their keep if someone owns
 * the "is this what we want?" question at record-time.
 *
 * <p>Each fixture exercises a distinct inferrer scenario:
 *
 * <ul>
 *   <li>{@code classic-star} — one fact with 3 FK dims and a DATE column; expect a shared Time
 *       dim and one role-playing usage.
 *   <li>{@code snowflake} — a dim table with its own single outgoing FK; expect
 *       {@code rule:snowflake-1hop} provenance and a two-level hierarchy with a join.
 *   <li>{@code multi-fact} — two facts sharing a customer dim; expect two cubes.
 *   <li>{@code wide-flat} — fact with many numeric columns and a date, FKs point to tables
 *       that aren't in the model so only the shared Time dim attaches. Expect 6 measures
 *       (5 SUM + Fact Count).
 *   <li>{@code ambiguous-fact} — two candidate fact tables, only one meets the
 *       {@code fkOut>=2 AND rowCount>=1000} threshold. Expect one cube (the other table is
 *       ORPHAN and silently dropped).
 * </ul>
 */
public class SchemaInferrerGoldenTest {

    private static final String UPDATE_PROPERTY = "schemagen.updateGolden";
    private static final String FIXTURE_DIR = "schemagen/fixtures/";

    private static final List<String> FIXTURES =
            Arrays.asList("classic-star", "snowflake", "multi-fact", "wide-flat", "ambiguous-fact");

    @Test
    public void goldenFixturesMatchInferrerOutput() throws IOException {
        boolean updateGolden = Boolean.getBoolean(UPDATE_PROPERTY);
        StringBuilder failures = new StringBuilder();
        boolean regenerated = false;

        for (String fixture : FIXTURES) {
            String resource = FIXTURE_DIR + fixture + ".json";
            String expectedResource = FIXTURE_DIR + fixture + ".expected.json";

            DbModel model;
            try (InputStream in =
                    SchemaInferrerGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    failures.append("[")
                            .append(fixture)
                            .append("] missing fixture: ")
                            .append(resource)
                            .append('\n');
                    continue;
                }
                model = DbModelJson.load(in);
            }

            DraftSchema draft = new SchemaInferrer().infer(model);
            String actual = DraftSchemaJson.toJson(draft);

            String expected = readResource(expectedResource);
            if (updateGolden || expected == null) {
                Path target = resolveExpectedPath(fixture);
                Files.writeString(target, actual, StandardCharsets.UTF_8);
                regenerated = true;
                failures.append("[")
                        .append(fixture)
                        .append("] wrote expected file: ")
                        .append(target)
                        .append('\n');
                continue;
            }

            // Content compare, not byte-exact line endings — a golden checked
            // out with CRLF (git core.autocrlf=true on Windows) must still match
            // the LF the generator emits, else these flake on every Windows clone.
            if (!normalizeEol(expected).equals(normalizeEol(actual))) {
                failures.append("[")
                        .append(fixture)
                        .append("] output differs from ")
                        .append(expectedResource)
                        .append("\n--- expected ---\n")
                        .append(expected)
                        .append("\n--- actual ---\n")
                        .append(actual)
                        .append('\n');
            }
        }

        if (failures.length() > 0) {
            if (regenerated) {
                fail("regenerated expected files — inspect diff and re-run without -D"
                        + UPDATE_PROPERTY
                        + ":\n"
                        + failures);
            }
            fail(failures.toString());
        }
    }

    /** Normalise CRLF/CR to LF so golden comparison is line-ending-agnostic. */
    private static String normalizeEol(String s) {
        return s == null ? null : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = SchemaInferrerGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Resolve the on-disk path of an {@code .expected.json} file for update-mode writes.
     * Uses the fixture's own classpath URL to locate the resources directory — works from
     * both {@code mvn test} and IDE runs, without hard-coding {@code src/test/resources}.
     */
    private static Path resolveExpectedPath(String fixture) {
        URL fixtureUrl = SchemaInferrerGoldenTest.class.getClassLoader().getResource(FIXTURE_DIR + fixture + ".json");
        if (fixtureUrl == null) {
            throw new IllegalStateException("cannot locate fixture on classpath: " + fixture);
        }
        // target/test-classes/schemagen/fixtures/foo.json -> src/test/resources/schemagen/fixtures/foo.expected.json
        Path classpathFile = Paths.get(fixtureUrl.getPath().replace("%20", " "));
        Path moduleRoot = classpathFile
                .getParent() // fixtures
                .getParent() // schemagen
                .getParent() // test-classes
                .getParent(); // target (parent is module root)
        Path resources = moduleRoot.getParent().resolve("src/test/resources/" + FIXTURE_DIR);
        // Prefer writing to src/test/resources so the generated file is part of the source tree;
        // fall back to the classpath location only if that directory doesn't exist.
        if (Files.isDirectory(resources)) {
            return resources.resolve(fixture + ".expected.json");
        }
        return classpathFile.resolveSibling(fixture + ".expected.json");
    }
}
