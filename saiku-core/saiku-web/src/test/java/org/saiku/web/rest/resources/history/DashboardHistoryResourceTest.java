/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources.history;

import static org.junit.Assert.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.history.DashboardHistoryService;
import org.saiku.service.history.DashboardVersion;
import org.saiku.web.service.SessionService;

/** Unit tests for {@link DashboardHistoryResource}. */
public class DashboardHistoryResourceTest {

    private static final String DASH = "board.saikudash";

    private DashboardHistoryResource resource;
    private StubHistory history;
    private StubDatasources datasources;

    @Before
    public void setUp() {
        resource = new DashboardHistoryResource();
        history = new StubHistory();
        datasources = new StubDatasources();
        resource.setHistoryService(history);
        resource.setDatasourceService(datasources);
        resource.setSessionService(new StubSessionService());
    }

    private static final class StubHistory extends DashboardHistoryService {
        final Map<String, List<DashboardVersion>> versions = new HashMap<>();

        @Override
        public DashboardVersion archive(String dashboardPath, String dashboardJson, String author) {
            DashboardVersion v = new DashboardVersion();
            v.version = "v" + versions.size();
            v.createdAt = 42L;
            v.author = author;
            v.dashboard = dashboardJson;
            versions.computeIfAbsent(dashboardPath, k -> new ArrayList<>()).add(v);
            return v;
        }

        @Override
        public List<DashboardVersion> list(String dashboardPath) {
            return versions.getOrDefault(dashboardPath, new ArrayList<>());
        }

        @Override
        public DashboardVersion getVersion(String dashboardPath, String versionId) {
            for (DashboardVersion v : list(dashboardPath)) {
                if (v.version.equals(versionId)) {
                    return v;
                }
            }
            return null;
        }
    }

    private static final class StubDatasources extends DatasourceService {
        String fileData = "{\"id\":\"b\"}";
        boolean saveFails;

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if ("boom.saikudash".equals(path)) {
                throw new IllegalStateException("nope");
            }
            return fileData;
        }

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
            return saveFails ? "Error saving file" : "Save Okay";
        }
    }

    private static final class StubSessionService extends SessionService {
        private final Map<String, Object> objects = new HashMap<>();

        StubSessionService() {
            objects.put("username", "admin");
        }

        @Override
        public Map<String, Object> getAllSessionObjects() {
            return objects;
        }
    }

    @Test
    public void list_requires_a_dashboard_parameter() {
        assertEquals(400, resource.list(null).getStatus());
    }

    @Test
    public void list_rejects_a_non_saikudash_path() {
        assertEquals(403, resource.list("board.json").getStatus());
    }

    @Test
    public void list_rejects_an_unreadable_dashboard() {
        assertEquals(403, resource.list("boom.saikudash").getStatus());
    }

    @Test
    public void list_returns_the_versions_of_a_readable_dashboard() {
        history.archive(DASH, "{\"v\":1}", "alice");
        history.archive(DASH, "{\"v\":2}", "bob");

        Response response = resource.list(DASH);
        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getEntity();
        assertEquals(2, rows.size());
        assertEquals("alice", rows.get(0).get("author"));
        assertEquals(42L, rows.get(0).get("createdAt"));
    }

    @Test
    public void version_requires_dashboard_and_version() {
        assertEquals(400, resource.version(null, "v0").getStatus());
        assertEquals(400, resource.version(DASH, null).getStatus());
    }

    @Test
    public void version_serves_the_stored_dashboard_json() {
        history.archive(DASH, "{\"v\":1}", "alice");
        Response response = resource.version(DASH, "v0");
        assertEquals(200, response.getStatus());
        assertEquals("{\"v\":1}", response.getEntity());
    }

    @Test
    public void version_reports_not_found_for_an_unknown_version() {
        assertEquals(404, resource.version(DASH, "missing").getStatus());
    }

    @Test
    public void restore_requires_dashboard_and_version() {
        assertEquals(400, resource.restore(null, "v0").getStatus());
        assertEquals(400, resource.restore(DASH, null).getStatus());
    }

    @Test
    public void restore_reports_not_found_for_an_unknown_version() {
        assertEquals(404, resource.restore(DASH, "missing").getStatus());
    }

    @Test
    public void restore_archives_the_current_dashboard_and_reports_ok() {
        history.archive(DASH, "{\"v\":1}", "alice");

        Response response = resource.restore(DASH, "v0");

        assertEquals(200, response.getStatus());
        assertEquals("OK", ((Map<?, ?>) response.getEntity()).get("status"));
        // The pre-restore current content is archived so the swap is reversible.
        assertEquals(2, history.list(DASH).size());
        assertEquals("{\"id\":\"b\"}", history.getVersion(DASH, "v1").dashboard);
    }

    @Test
    public void restore_fails_closed_when_the_save_does_not_say_okay() {
        history.archive(DASH, "{\"v\":1}", "alice");
        datasources.saveFails = true;

        assertEquals(403, resource.restore(DASH, "v0").getStatus());
        // Nothing new was archived on the failed restore.
        assertEquals(1, history.list(DASH).size());
    }
}
