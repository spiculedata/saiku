/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;

/**
 * Tests for {@link DatasourceBackedModelStore}.
 *
 * <p>The path-sanitising cases are the point. {@code modelName} arrives from a REST body, and
 * without sanitising a value like {@code ../../webapps/ROOT} would write outside
 * {@code /datasources/} — into the deployed webapp, in a WAR layout. That is a remote file write
 * reachable by any admin, so the cases below are asserted directly rather than inferred.
 */
public class DatasourceBackedModelStoreTest {

    /** Records what was written where; no repository layer needed. */
    private static final class RecordingDatasourceService extends DatasourceService {
        final Map<String, String> files = new java.util.LinkedHashMap<>();
        String returnValue = "ok";

        @Override
        public String saveInternalFile(String path, String content, String type) {
            files.put(path, content);
            return returnValue;
        }

        @Override
        public String getInternalFileData(String path) {
            return files.get(path);
        }
    }

    @Test
    public void writesBothArtefactsUnderTheExpectedNames() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("foodmart", "foodmart", "semantic_model: []", "# rationale");

        assertEquals("semantic_model: []", ds.files.get("/datasources/foodmart.generated.yaml"));
        assertEquals("# rationale", ds.files.get("/datasources/foodmart.generated.rationale.md"));
    }

    @Test
    public void blankModelNameFallsBackToTheDataSourceId() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("foodmart", null, "y", "r");

        assertTrue(ds.files.containsKey("/datasources/foodmart.generated.yaml"));
        assertTrue(ds.files.containsKey("/datasources/foodmart.generated.rationale.md"));
    }

    @Test
    public void pathTraversalInTheModelNameCannotEscapeDatasources() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("ds", "../../webapps/ROOT", "y", "r");

        for (String path : new ArrayList<>(ds.files.keySet())) {
            assertTrue("wrote outside /datasources/: " + path, path.startsWith("/datasources/"));
            assertTrue("traversal survived: " + path, !path.contains(".."));
        }
    }

    @Test
    public void absolutePathInTheModelNameCannotEscapeDatasources() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("ds", "/etc/passwd", "y", "r");

        for (String path : new ArrayList<>(ds.files.keySet())) {
            assertTrue("wrote outside /datasources/: " + path, path.startsWith("/datasources/"));
        }
    }

    @Test
    public void aNameOfOnlyDotsDoesNotResolveToADirectory() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("ds", "..", "y", "r");

        assertEquals(
                List.of("/datasources/_.generated.yaml", "/datasources/_.generated.rationale.md"),
                new ArrayList<>(ds.files.keySet()));
    }

    @Test
    public void spacesAndOtherShellAwkwardCharactersAreReplaced() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        store.write("ds", "my model; rm -rf /", "y", "r");

        String yamlPath = new ArrayList<>(ds.files.keySet()).get(0);
        assertTrue("unexpected path: " + yamlPath, yamlPath.matches("/datasources/[A-Za-z0-9._-]+\\.generated\\.yaml"));
    }

    @Test
    public void anUnusableNameStillProducesAWriteablePath() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        // Neither input usable: the run must still have somewhere to write rather than NPE.
        store.write(null, null, "y", "r");

        assertEquals(
                List.of("/datasources/generated.generated.yaml", "/datasources/generated.generated.rationale.md"),
                new ArrayList<>(ds.files.keySet()));
    }

    @Test
    public void pathsForMatchesWhereWriteActuallyLands() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        GeneratedModelStore.WrittenPaths declared = store.pathsFor("foodmart", "foodmart");
        store.write("foodmart", "foodmart", "y", "r");

        // The rationale document names these paths before the write, so a drift here would put a
        // wrong path in the audit trail.
        assertEquals(List.copyOf(ds.files.keySet()), List.of(declared.yamlPath(), declared.rationalePath()));
    }

    @Test
    public void readExistingReturnsThePriorModel() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);
        store.write("foodmart", "foodmart", "semantic_model: []", "r");

        assertEquals(
                "semantic_model: []", store.readExisting("foodmart", "foodmart").orElseThrow());
    }

    @Test
    public void readExistingIsEmptyOnAFirstRun() {
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(new RecordingDatasourceService());
        assertTrue(store.readExisting("foodmart", "foodmart").isEmpty());
    }

    @Test
    public void readExistingIsEmptyForAnEmptyFile() {
        RecordingDatasourceService ds = new RecordingDatasourceService();
        ds.files.put("/datasources/foodmart.generated.yaml", "");
        DatasourceBackedModelStore store = new DatasourceBackedModelStore(ds);

        assertTrue(store.readExisting("foodmart", "foodmart").isEmpty());
    }
}
