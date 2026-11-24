/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
 * Unit coverage for {@link DashboardHistoryService} (issue #947): archive →
 * list (newest-first), exact-snapshot retrieval, retain-{@value
 * DashboardHistoryService#RETENTION} pruning, empty/missing handling, and the
 * traversal-flattened storage path. In-memory {@link DatasourceService} stub.
 */
public class DashboardHistoryServiceTest {

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
    private DashboardHistoryService svc;
    private static final String DASH = "dashboards/foo.saikudash";

    @Before
    public void setUp() {
        ds = new FakeDs();
        svc = new DashboardHistoryService();
        svc.setDatasourceService(ds);
    }

    @Test
    public void archive_then_list_newest_first() {
        svc.archive(DASH, "{\"v\":1}", "admin");
        svc.archive(DASH, "{\"v\":2}", "bob");
        List<DashboardVersion> list = svc.list(DASH);
        assertEquals(2, list.size());
        assertEquals("newest first", "{\"v\":2}", list.get(0).dashboard);
        assertEquals("bob", list.get(0).author);
        assertEquals("{\"v\":1}", list.get(1).dashboard);
    }

    @Test
    public void getVersion_returns_exact_snapshot() {
        DashboardVersion v = svc.archive(DASH, "{\"layout\":{\"tiles\":[]}}", "admin");
        assertNotNull(v.version);
        DashboardVersion got = svc.getVersion(DASH, v.version);
        assertNotNull(got);
        assertEquals("{\"layout\":{\"tiles\":[]}}", got.dashboard);
        assertNull("unknown version id", svc.getVersion(DASH, "nope"));
    }

    @Test
    public void retention_prunes_oldest_beyond_50() {
        for (int i = 1; i <= 55; i++) {
            svc.archive(DASH, "{\"v\":" + i + "}", "admin");
        }
        List<DashboardVersion> list = svc.list(DASH);
        assertEquals(DashboardHistoryService.RETENTION, list.size());
        assertEquals("newest kept", "{\"v\":55}", list.get(0).dashboard);
        assertEquals("oldest kept is v6 (v1-5 pruned)", "{\"v\":6}", list.get(list.size() - 1).dashboard);
    }

    @Test
    public void purge_removes_the_history_file() {
        svc.archive(DASH, "{\"v\":1}", "admin");
        assertEquals(1, svc.list(DASH).size());
        svc.purge(DASH);
        assertEquals("purged history is empty", 0, svc.list(DASH).size());
    }

    @Test
    public void blank_and_missing_are_safe() {
        assertNull("blank content is a no-op", svc.archive(DASH, "  ", "admin"));
        assertEquals(0, svc.list("dashboards/never.saikudash").size());
        assertNull(svc.getVersion("dashboards/never.saikudash", "x"));
    }

    @Test
    public void history_path_is_hashed_namespaced_and_flat() {
        // saiku#2550d: hashed off the EXACT path (see DashboardSidecars) — distinct
        // paths get distinct files, still flat at the datadir root.
        String flat = DashboardHistoryService.historyPath("dashboards/foo.saikudash");
        assertTrue(flat.startsWith("saiku-history-v2-"));
        assertTrue(flat.endsWith(".jsonl"));
        assertFalse(flat.contains("/") || flat.contains("\\"));
        String evil = DashboardHistoryService.historyPath("../../etc/passwd");
        assertTrue(evil.startsWith("saiku-history-v2-"));
        assertFalse(evil.contains(".."));
        assertFalse(evil.contains("/") || evil.contains("\\"));
        // Paths differing only by / versus _ map to DIFFERENT files.
        assertNotEquals(
                DashboardHistoryService.historyPath("homes/a_b/x.saikudash"),
                DashboardHistoryService.historyPath("homes/a/b/x.saikudash"));
    }

    @Test
    public void sidecar_from_the_old_naming_is_still_found_and_retired_on_write() {
        // saiku#2550d: legacy history files stay readable after upgrade...
        String legacy = "saiku-history-" + DASH.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
        ds.internal.put(legacy, "{\"version\":\"v1\",\"createdAt\":1,\"author\":\"admin\",\"dashboard\":\"{}\"}\n");
        assertEquals(1, svc.list(DASH).size());
        assertNotNull(svc.getVersion(DASH, "v1"));

        // ...and the next write retires the legacy file into the hashed one.
        svc.archive(DASH, "{}", "admin");
        assertNull(ds.internal.get(legacy));
        assertEquals(2, svc.list(DASH).size());
    }
}
