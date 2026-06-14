/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
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
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
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
 * <p>Runs as a JUnit 4 {@code @Parameterized} suite so each fixture
 * shows up as its own surefire test row — a deletion of one fixture
 * shows up in CI logs and counts as a distinct failure, instead of
 * being lumped into a single "all goldens" verdict.
 *
 * <p><b>Regenerating goldens.</b> When a converter change is intentional:
 * <pre>{@code
 *   mvn -pl saiku-core/saiku-service test \
 *       -Dtest=MdxEchoGoldenTest -Dsaiku.goldens.update=true
 * }</pre>
 * Then review the diff in git before committing — golden tests only
 * earn their keep if a human owned the "is this what we want?" question.
 */
@RunWith(Parameterized.class)
public class MdxEchoGoldenTest {

    private static final String UPDATE_PROPERTY = "saiku.goldens.update";
    private static final String FIXTURE_DIR = "mdx-goldens/";

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() {
        return Arrays.asList(new Object[][] {
            {"01-single-measure-no-rows"},
            {"02-measure-with-row"},
            {"03-two-measures"},
            {"04-cross-hier-rows"},
            {"05-non-empty"},
            {"06-numeric-level-salary"}
        });
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSchemaConverter converter = new AiSchemaConverter();
    private final AiSchema schema = QuirksTestFixture.directSchema();
    private final String fixture;

    public MdxEchoGoldenTest(String fixture) {
        this.fixture = fixture;
    }

    @Test
    public void goldenMatchesConverterOutput() throws IOException {
        boolean updateGolden = Boolean.getBoolean(UPDATE_PROPERTY);
        String requestResource = FIXTURE_DIR + fixture + "/request.json";
        String expectedResource = FIXTURE_DIR + fixture + "/expected.mdx";

        AiQueryRequest req;
        try (InputStream in = MdxEchoGoldenTest.class.getClassLoader().getResourceAsStream(requestResource)) {
            assertNotNull("missing fixture request.json on classpath: " + requestResource, in);
            req = MAPPER.readValue(in, AiQueryRequest.class);
        }
        req.setCube(QuirksTestFixture.cubeRef());

        ThinQuery tq = converter.convert(req, schema);
        assertNotNull("converter must produce a ThinQuery", tq);
        String actual = tq.getMdx();
        assertNotNull("ThinQuery.mdx must be non-null", actual);

        String expected = readResource(expectedResource);
        if (updateGolden || expected == null) {
            Path target = resolveExpectedPath(fixture);
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual, StandardCharsets.UTF_8);
            // Update-mode always fails so the developer sees the diff and
            // re-runs without the flag — golden tests only earn their
            // keep if a human owns the "is this what we want?" question.
            fail("wrote " + target + " — inspect diff and re-run without -D" + UPDATE_PROPERTY);
        }

        // Compare on content, not byte-exact line endings: a golden checked out
        // with CRLF (git core.autocrlf=true on Windows) must still match the
        // LF the converter emits — otherwise these goldens flake on every
        // Windows clone with a spurious \r\n-vs-\n diff (no .gitattributes pins
        // the fixtures to LF).
        assertEquals("MDX differs from " + expectedResource, normalizeEol(expected), normalizeEol(actual));
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = MdxEchoGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Normalise CRLF/CR to LF so golden comparison is line-ending-agnostic. */
    private static String normalizeEol(String s) {
        return s == null ? null : s.replace("\r\n", "\n").replace('\r', '\n');
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
