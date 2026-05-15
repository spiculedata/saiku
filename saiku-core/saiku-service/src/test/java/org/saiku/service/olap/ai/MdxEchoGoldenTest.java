/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.saiku.olap.query2.ThinQuery;

/**
 * MDX-echo audit (Phase 4 / saiku#9). Each fixture under
 * {@code src/test/resources/mdx-goldens/<name>/} pairs a
 * {@code request.json} (deserialised to {@link AiQueryRequest}) with an
 * {@code expected.mdx} pinning the converter's emitted MDX byte-for-byte.
 *
 * <p>Catches the saiku#796/#809-class drift where a converter change
 * silently reshapes MDX (extra hierarchy qualifier, dropped Hierarchize,
 * wrong member-order) but still parses against Mondrian. Property tests
 * (AiQueryRequestPropertyTest) prove the converter doesn't throw on
 * generated input; these goldens prove the output stays stable.
 *
 * <p>The cube ref is injected from {@link QuirksTestFixture#cubeRef()}
 * rather than embedded in every request.json — focuses the goldens on
 * shape, not cube identification (which is covered by canonical-cube
 * tests).
 *
 * <p><b>Regenerating goldens.</b> When a converter change is intentional:
 * <pre>{@code
 *   mvn -pl saiku-core/saiku-service test \
 *       -Dtest=MdxEchoGoldenTest -Dsaiku.goldens.update=true
 * }</pre>
 * Then review the diff in git before committing — golden tests only
 * earn their keep if a human owned the "is this what we want?" question.
 */
public class MdxEchoGoldenTest {

    private static final String UPDATE_PROPERTY = "saiku.goldens.update";
    private static final String FIXTURE_DIR = "mdx-goldens/";

    private static final List<String> FIXTURES = Arrays.asList(
            "01-single-measure-no-rows",
            "02-measure-with-row",
            "03-two-measures",
            "04-cross-hier-rows",
            "05-non-empty",
            "06-numeric-level-salary");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AiSchemaConverter converter = new AiSchemaConverter();
    private final AiSchema schema = QuirksTestFixture.directSchema();

    @Test
    public void goldenFixturesMatchConverterOutput() throws IOException {
        boolean updateGolden = Boolean.getBoolean(UPDATE_PROPERTY);
        StringBuilder failures = new StringBuilder();
        boolean regenerated = false;

        for (String fixture : FIXTURES) {
            String requestResource = FIXTURE_DIR + fixture + "/request.json";
            String expectedResource = FIXTURE_DIR + fixture + "/expected.mdx";

            AiQueryRequest req;
            try (InputStream in = MdxEchoGoldenTest.class.getClassLoader().getResourceAsStream(requestResource)) {
                if (in == null) {
                    failures.append("[")
                            .append(fixture)
                            .append("] missing request.json: ")
                            .append(requestResource)
                            .append('\n');
                    continue;
                }
                req = MAPPER.readValue(in, AiQueryRequest.class);
            }
            // Cube ref injected here so request.json fixtures stay focused on shape.
            req.setCube(QuirksTestFixture.cubeRef());

            ThinQuery tq = converter.convert(req, schema);
            assertNotNull("converter must produce a ThinQuery for fixture " + fixture, tq);
            String actual = tq.getMdx();
            assertNotNull("ThinQuery.mdx must be non-null for fixture " + fixture, actual);

            String expected = readResource(expectedResource);
            if (updateGolden || expected == null) {
                Path target = resolveExpectedPath(fixture);
                Files.createDirectories(target.getParent());
                Files.writeString(target, actual, StandardCharsets.UTF_8);
                regenerated = true;
                failures.append("[")
                        .append(fixture)
                        .append("] wrote expected file: ")
                        .append(target)
                        .append('\n');
                continue;
            }

            if (!expected.equals(actual)) {
                failures.append("[")
                        .append(fixture)
                        .append("] MDX differs from ")
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
                fail("regenerated goldens — inspect diff and re-run without -D" + UPDATE_PROPERTY + ":\n" + failures);
            }
            fail(failures.toString());
        }
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = MdxEchoGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Mirrors {@code SchemaInferrerGoldenTest.resolveExpectedPath} — uses
     * the classpath URL so update-mode writes work under both {@code mvn
     * test} and IDE runs without hard-coding {@code src/test/resources}.
     */
    private static Path resolveExpectedPath(String fixture) {
        URL fixtureUrl = MdxEchoGoldenTest.class.getClassLoader().getResource(FIXTURE_DIR + fixture + "/request.json");
        if (fixtureUrl == null) {
            throw new IllegalStateException("cannot locate fixture on classpath: " + fixture);
        }
        Path classpathFile = Paths.get(fixtureUrl.getPath().replace("%20", " "));
        Path moduleRoot = classpathFile
                .getParent() // <fixture>
                .getParent() // mdx-goldens
                .getParent() // test-classes
                .getParent(); // target (parent is module root)
        Path resources = moduleRoot.getParent().resolve("src/test/resources/" + FIXTURE_DIR + fixture);
        if (Files.isDirectory(resources.getParent())) {
            return resources.resolve("expected.mdx");
        }
        return classpathFile.resolveSibling("expected.mdx");
    }
}
