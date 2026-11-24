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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.util.DashboardSidecars;

/**
 * Unit coverage for {@link DashboardHistoryService} (issue #947): archive →
 * list (newest-first), exact-snapshot retrieval, retain-{@value
 * DashboardHistoryService#RETENTION} pruning, empty/missing handling, and the
 * traversal-flattened storage path. In-memory {@link DatasourceService} stub.
 */
public class DashboardHistoryServiceTest {

    private static class FakeDs extends DatasourceService {
        final Map<String, String> internal = new HashMap<>();
        final List<String> dashboards = new ArrayList<>();

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

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles) {
            List<IRepositoryObject> files = new ArrayList<>();
            for (String path : dashboards) {
                files.add(new RepositoryFileObject("x.saikudash", "#" + path, "saikudash", "/" + path, List.of()));
            }
            return files;
        }
    }

    private FakeDs ds;
    private DashboardHistoryService svc;
    private static final String DASH = "dashboards/foo.saikudash";

    @Before
    public void setUp() {
        ds = new FakeDs();
        ds.dashboards.add(DASH);
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

    /** saiku#2550d (review): purge must also remove the LEGACY history sidecar,
     *  or a deleted dashboard's pre-upgrade versions pass to the next dashboard
     *  flattening to the same name (and deleted-after-upgrade entries come back
     *  when a dashboard is recreated at the same path). */
    @Test
    public void purge_removes_the_legacy_file_so_a_recreated_dashboard_starts_clean() {
        String legacy = DashboardSidecars.legacyPath(DashboardHistoryService.KIND, DASH);
        ds.internal.put(legacy, "{\"dashboard\":\"{\\\"v\\\":1}\",\"author\":\"admin\",\"ts\":1}\n");
        svc.purge(DASH);
        assertNull("legacy file removed with the dashboard", ds.internal.get(legacy));
        assertEquals(0, svc.list(DASH).size());
    }

    /** homes/a_b/x and homes/a/b/x flatten to one legacy name: the file goes
     *  only when the deleted dashboard is its sole flattened owner. */
    @Test
    public void purge_keeps_an_ambiguous_legacy_file_while_another_dashboard_shares_the_flattened_name() {
        String ab = "homes/a_b/x.saikudash";
        String abx = "homes/a/b/x.saikudash";
        ds.dashboards.clear();
        ds.dashboards.add(ab);
        ds.dashboards.add(abx);
        String legacy = DashboardSidecars.legacyPath(DashboardHistoryService.KIND, ab);
        ds.internal.put(legacy, "{\"dashboard\":\"{}\",\"author\":\"admin\",\"ts\":1}\n");

        svc.purge(ab);
        assertNotNull("ambiguous legacy file kept while another dashboard shares the flattened name", ds.internal.get(legacy));
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
    public void unambiguous_legacy_sidecar_is_found_and_preserved_on_write() {
        // saiku#2550d: legacy history files stay readable after upgrade...
        String legacy = "saiku-history-" + DASH.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
        ds.internal.put(legacy, "{\"version\":\"v1\",\"createdAt\":1,\"author\":\"admin\",\"dashboard\":\"{}\"}\n");
        assertEquals(1, svc.list(DASH).size());
        assertNotNull(svc.getVersion(DASH, "v1"));

        // A request write never destroys the legacy file; the v2 copy takes precedence.
        svc.archive(DASH, "{}", "admin");
        assertNotNull(ds.internal.get(legacy));
        assertEquals(2, svc.list(DASH).size());
    }

    @Test
    public void colliding_legacy_history_is_not_read_or_stolen_by_either_dashboard() {
        String owner = "homes/a_b/x.saikudash";
        String other = "homes/a/b/x.saikudash";
        ds.dashboards.clear();
        ds.dashboards.add(owner);
        ds.dashboards.add(other);
        String legacy = "saiku-history-" + owner.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
        String original = "{\"version\":\"v1\",\"dashboard\":\"owner\"}\n";
        ds.internal.put(legacy, original);

        assertTrue(svc.list(owner).isEmpty());
        assertTrue(svc.list(other).isEmpty());
        svc.archive(other, "other", "bob");
        assertEquals(1, svc.list(other).size());
        assertTrue(svc.list(owner).isEmpty());
        assertEquals(original, ds.internal.get(legacy));
    }
}
