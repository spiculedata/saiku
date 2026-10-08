/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;

/**
 * Unit coverage for {@link SchemaHistoryService} (issue #1121, Phase 1): archive → list
 * (newest-first) with both old+new YAML retained, CREATE-vs-UPDATE action inference, exact-entry
 * retrieval, retain-{@value SchemaHistoryService#RETENTION} pruning, empty/missing handling, and
 * the traversal-flattened storage path. In-memory {@link DatasourceService} stub, same shape as
 * {@code DashboardHistoryServiceTest} (#947).
 */
public class SchemaHistoryServiceTest {

    private static class FakeDs extends DatasourceService {
        final Map<String, String> internal = new HashMap<>();

        @Override
        public String saveInternalFile(String path, String content, String type) {
            internal.put(path, content);
            return "Save Okay";
        }

        @Override
        public String getInternalFileData(String path) {
            return internal.get(path);
        }

        @Override
        public void removeInternalFile(String path) {
            internal.remove(path);
        }
    }

    private FakeDs ds;
    private SchemaHistoryService svc;
    private static final String TARGET = "sales-ossie";

    @Before
    public void setUp() {
        ds = new FakeDs();
        svc = new SchemaHistoryService();
        svc.setDatasourceService(ds);
    }

    @Test
    public void first_write_is_recorded_as_create() {
        SchemaVersion v = svc.archive(TARGET, null, "semantic_model: []", "admin");
        assertNotNull(v);
        assertEquals("CREATE", v.action);
        assertNull(v.oldYaml);
        assertEquals("semantic_model: []", v.newYaml);
    }

    @Test
    public void subsequent_write_is_recorded_as_update_with_both_sides() {
        svc.archive(TARGET, null, "v1", "admin");
        SchemaVersion v2 = svc.archive(TARGET, "v1", "v2", "bob");
        assertEquals("UPDATE", v2.action);
        assertEquals("v1", v2.oldYaml);
        assertEquals("v2", v2.newYaml);
    }

    @Test
    public void archive_then_list_newest_first() {
        svc.archive(TARGET, null, "v1", "admin");
        svc.archive(TARGET, "v1", "v2", "bob");
        List<SchemaVersion> list = svc.list(TARGET);
        assertEquals(2, list.size());
        assertEquals("newest first", "v2", list.get(0).newYaml);
        assertEquals("bob", list.get(0).author);
        assertEquals("v1", list.get(1).newYaml);
    }

    @Test
    public void getVersion_returns_exact_entry() {
        SchemaVersion v = svc.archive(TARGET, null, "semantic_model: []", "admin");
        assertNotNull(v.id);
        SchemaVersion got = svc.getVersion(TARGET, v.id);
        assertNotNull(got);
        assertEquals("semantic_model: []", got.newYaml);
        assertEquals(TARGET, got.target);
        assertNull("unknown version id", svc.getVersion(TARGET, "nope"));
    }

    @Test
    public void retention_prunes_oldest_beyond_50() {
        String prev = null;
        for (int i = 1; i <= 55; i++) {
            String next = "v" + i;
            svc.archive(TARGET, prev, next, "admin");
            prev = next;
        }
        List<SchemaVersion> list = svc.list(TARGET);
        assertEquals(SchemaHistoryService.RETENTION, list.size());
        assertEquals("newest kept", "v55", list.get(0).newYaml);
        assertEquals("oldest kept is v6 (v1-5 pruned)", "v6", list.get(list.size() - 1).newYaml);
    }

    @Test
    public void blank_and_missing_are_safe() {
        assertNull("blank new content is a no-op", svc.archive(TARGET, null, "  ", "admin"));
        assertEquals(0, svc.list("never-heard-of-it").size());
        assertNull(svc.getVersion("never-heard-of-it", "x"));
    }

    @Test
    public void history_path_is_flattened_and_namespaced() {
        assertEquals("saiku-schema-history-sales-ossie.jsonl", SchemaHistoryService.historyPath("sales-ossie"));
        String evil = SchemaHistoryService.historyPath("../../etc/passwd");
        assertTrue(evil.startsWith("saiku-schema-history-"));
        assertFalse(evil.contains(".."));
        assertFalse(evil.contains("/") || evil.contains("\\"));
    }
}
