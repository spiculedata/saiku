/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.history.SchemaHistoryService;
import org.saiku.service.history.SchemaVersion;

/**
 * Tests for {@link SchemaHistoryResource} (issue #1121, Phase 1): list newest-first (metadata
 * only) and the full-entry version lookup an admin's "expand diff" click drives. {@code
 * userService} is left null so the admin guard no-ops (headless), same convention as {@code
 * CubeDesignerResourceTest}.
 */
public class SchemaHistoryResourceTest {

    private static final String TARGET = "sales-ossie";

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
    }

    private SchemaHistoryService historyService;
    private SchemaHistoryResource resource;

    @Before
    public void setUp() {
        historyService = new SchemaHistoryService();
        historyService.setDatasourceService(new FakeDs());

        resource = new SchemaHistoryResource();
        resource.setHistoryService(historyService);
    }

    @Test
    public void list_requiresTarget() {
        Response r = resource.list(null);
        assertEquals(400, r.getStatus());
    }

    @Test
    public void list_returnsMetadataNewestFirst_withoutYamlBodies() {
        historyService.archive(TARGET, null, "v1", "admin");
        historyService.archive(TARGET, "v1", "v2", "bob");

        Response r = resource.list(TARGET);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) r.getEntity();
        assertEquals(2, body.size());
        assertEquals("bob", body.get(0).get("author"));
        assertEquals("UPDATE", body.get(0).get("action"));
        assertEquals(TARGET, body.get(0).get("target"));
        assertFalse("list entries must not carry the YAML bodies", body.get(0).containsKey("newYaml"));
        assertEquals("admin", body.get(1).get("author"));
        assertEquals("CREATE", body.get(1).get("action"));
    }

    @Test
    public void version_returnsFullEntry_forDiffExpansion() {
        SchemaVersion v = historyService.archive(TARGET, "v1", "v2", "bob");

        Response r = resource.version(TARGET, v.id);
        assertEquals(200, r.getStatus());
        SchemaVersion body = (SchemaVersion) r.getEntity();
        assertEquals("v1", body.oldYaml);
        assertEquals("v2", body.newYaml);
    }

    @Test
    public void version_requiresTargetAndVersion() {
        assertEquals(400, resource.version(null, "x").getStatus());
        assertEquals(400, resource.version(TARGET, null).getStatus());
    }

    @Test
    public void version_returnsNotFound_forUnknownId() {
        Response r = resource.version(TARGET, "nope");
        assertEquals(404, r.getStatus());
    }
}
