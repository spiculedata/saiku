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
    public void history_path_is_flattened_and_namespaced() {
        assertEquals(
                "saiku-history-dashboards_foo.saikudash.jsonl",
                DashboardHistoryService.historyPath("dashboards/foo.saikudash"));
        String evil = DashboardHistoryService.historyPath("../../etc/passwd");
        assertTrue(evil.startsWith("saiku-history-"));
        assertFalse(evil.contains(".."));
        assertFalse(evil.contains("/") || evil.contains("\\"));
    }
}
