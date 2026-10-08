/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.history.SchemaHistoryService;
import org.saiku.service.history.SchemaVersion;

/**
 * Tests for {@link OssieSchemaResource} (issue #1121, Phase 1): the raw YAML write path that
 * archives every save to {@link SchemaHistoryService}. {@code userService} is left null so the
 * admin guard no-ops (headless), same convention as {@code CubeDesignerResourceTest}; saiku-web
 * has no Mockito, so datasource lookup is a hand-rolled stub.
 */
public class OssieSchemaResourceTest {

    private static final String DATA_SOURCE_ID = "sales-ossie";
    private static final String VALID_YAML = "version: 0.1.0\nsemantic_model: []\n";

    private Path yamlFile;
    private OssieSchemaResource resource;
    private SchemaHistoryService historyService;

    private static class FakeDs extends DatasourceService {
        final Map<String, SaikuDatasource> byName = new HashMap<>();
        final Map<String, String> internal = new HashMap<>();

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return byName.get(datasourceName);
        }

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

    @Before
    public void setUp() throws IOException {
        yamlFile = Files.createTempDirectory("ossie-schema-test").resolve("sales.ossie.yaml");

        Properties props = new Properties();
        props.setProperty(ISaikuConnection.OSSIE_YAML_KEY, yamlFile.toString());
        SaikuDatasource ds = new SaikuDatasource(DATA_SOURCE_ID, SaikuDatasource.Type.OSSIE, props);

        FakeDs ds2 = new FakeDs();
        ds2.byName.put(DATA_SOURCE_ID, ds);

        historyService = new SchemaHistoryService();
        historyService.setDatasourceService(ds2);

        resource = new OssieSchemaResource();
        resource.setDatasourceService(ds2);
        resource.setHistoryService(historyService);
    }

    @Test
    public void get_returnsNotFound_whenNoSchemaSavedYet() {
        Response r = resource.get(DATA_SOURCE_ID);
        assertEquals(404, r.getStatus());
    }

    @Test
    public void save_createsFile_andRecordsCreateAction() throws IOException {
        Response r = resource.save(DATA_SOURCE_ID, VALID_YAML);
        assertEquals(200, r.getStatus());
        assertEquals(VALID_YAML, Files.readString(yamlFile, StandardCharsets.UTF_8));

        Response getResp = resource.get(DATA_SOURCE_ID);
        assertEquals(200, getResp.getStatus());
        assertEquals(VALID_YAML, getResp.getEntity());

        List<SchemaVersion> history = historyService.list(DATA_SOURCE_ID);
        assertEquals(1, history.size());
        assertEquals("CREATE", history.get(0).action);
        assertNull(history.get(0).oldYaml);
        assertEquals(VALID_YAML, history.get(0).newYaml);
    }

    @Test
    public void save_overwritesFile_andRecordsUpdateAction() {
        resource.save(DATA_SOURCE_ID, VALID_YAML);
        String updated = "version: 0.1.0\nsemantic_model:\n  - name: Sales\n";

        Response r = resource.save(DATA_SOURCE_ID, updated);
        assertEquals(200, r.getStatus());

        List<SchemaVersion> history = historyService.list(DATA_SOURCE_ID);
        assertEquals(2, history.size());
        SchemaVersion latest = history.get(0);
        assertEquals("UPDATE", latest.action);
        assertEquals(VALID_YAML, latest.oldYaml);
        assertEquals(updated, latest.newYaml);
    }

    @Test
    public void save_rejectsMalformedYaml_andLeavesDiskUntouched() throws IOException {
        Response r = resource.save(DATA_SOURCE_ID, "not: [valid, yaml");
        assertEquals(400, r.getStatus());
        assertTrue("no file should have been created for a rejected save", Files.notExists(yamlFile));
        assertEquals(0, historyService.list(DATA_SOURCE_ID).size());
    }

    @Test
    public void save_rejectsBlankBody() {
        Response r = resource.save(DATA_SOURCE_ID, "  ");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void save_rejectsUnknownDatasource() {
        Response r = resource.save("does-not-exist", VALID_YAML);
        assertEquals(400, r.getStatus());
    }

    @Test
    public void save_rejectsNonOssieDatasource() {
        FakeDs ds2 = new FakeDs();
        ds2.byName.put("mdx-ds", new SaikuDatasource("mdx-ds", SaikuDatasource.Type.OLAP, new Properties()));
        OssieSchemaResource mdxResource = new OssieSchemaResource();
        mdxResource.setDatasourceService(ds2);
        mdxResource.setHistoryService(historyService);

        Response r = mdxResource.save("mdx-ds", VALID_YAML);
        assertEquals(400, r.getStatus());
    }
}
