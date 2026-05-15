/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Schema-shape snapshot tests (Phase 3.B / #6). Pins the JSON shape of
 * the {@link AiSchema} response per cube as a checked-in baseline.
 * Any structural change — added/renamed/removed field, dimension
 * ordering, alias-map shape, requestSchema schema — must flip these
 * tests, forcing a deliberate snapshot update rather than a silent
 * drift in the agent-facing contract.
 *
 * <p>Currently snapshots the {@code Quirks} fixture cube (Phase 3.A);
 * adding FoodMart cubes is a mechanical follow-up — the test framework
 * here is general.
 *
 * <p>Updating snapshots: re-run with
 * {@code -Dsaiku.snapshots.update=true} to regenerate the baseline
 * files from the live schema. Review the diff in git before
 * committing.
 */
public class SchemaSnapshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Snapshot baseline lives in src/test/resources/schema-snapshots/. */
    private static final String SNAPSHOTS_DIR = "schema-snapshots";

    @Test
    public void quirksCubeSchemaMatchesSnapshot() throws Exception {
        OlapAiCubeMetadataService svc = new OlapAiCubeMetadataService();
        svc.setDiscoverService(QuirksTestFixture.discover());
        AiSchema schema = svc.getSchema(QuirksTestFixture.cubeRef());

        assertMatchesSnapshot("quirks-schema", schema);
    }

    /**
     * Read the baseline JSON, compare structurally to the freshly-built
     * schema. On mismatch, fail with the unified diff. If the
     * {@code saiku.snapshots.update} system property is true, overwrite
     * the baseline with the current shape.
     */
    private void assertMatchesSnapshot(String name, AiSchema actual) throws IOException {
        String actualJson = MAPPER.writeValueAsString(actual);

        if (Boolean.getBoolean("saiku.snapshots.update")) {
            Path snapPath = snapshotFilePath(name);
            Files.createDirectories(snapPath.getParent());
            Files.writeString(snapPath, actualJson, StandardCharsets.UTF_8);
            System.out.println("[snapshot] updated " + snapPath);
            return;
        }

        String expectedJson = loadSnapshot(name);
        // Round-trip through Jackson so the comparison is structural,
        // not whitespace-sensitive — JsonNode.equals does deep equality.
        JsonNode expectedNode = MAPPER.readTree(expectedJson);
        JsonNode actualNode = MAPPER.readTree(actualJson);

        if (!expectedNode.equals(actualNode)) {
            fail("Schema snapshot for '" + name + "' diverged.\n"
                    + "Expected:\n" + MAPPER.writeValueAsString(expectedNode) + "\n"
                    + "Actual:\n" + actualJson + "\n"
                    + "If the change is intentional, regenerate with:\n"
                    + "  mvn -pl saiku-core/saiku-service test "
                    + "-Dtest=SchemaSnapshotTest -Dsaiku.snapshots.update=true\n"
                    + "Then review the diff in git before committing.");
        }
        // Spot-assert top-level identity even on equality success, so a
        // future Jackson re-ordering or comparator change doesn't silently
        // mask a regression.
        assertEquals(expectedNode.get("cubeName"), actualNode.get("cubeName"));
    }

    private String loadSnapshot(String name) throws IOException {
        String path = SNAPSHOTS_DIR + "/" + name + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Snapshot baseline not found on classpath: " + path
                        + "\nGenerate it first with:\n"
                        + "  mvn -pl saiku-core/saiku-service test "
                        + "-Dtest=SchemaSnapshotTest -Dsaiku.snapshots.update=true");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Where to write a regenerated snapshot. Sits next to the
     *  baseline-on-classpath copy so git diff sees it on the next run. */
    private Path snapshotFilePath(String name) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("src/test/resources/" + SNAPSHOTS_DIR + "/" + name + ".json");
        if (Files.exists(candidate.getParent())) return candidate;
        return cwd.resolve("saiku-core/saiku-service/src/test/resources/" + SNAPSHOTS_DIR + "/" + name + ".json");
    }
}
