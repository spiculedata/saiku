/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.ossie.converter.OssieValidationReport;
import org.saiku.service.ossie.converter.VendorModelConverterRegistry;
import org.saiku.service.ossie.converter.VendorModelImportService;
import org.saiku.service.user.UserService;

/**
 * saiku#1730 — the REST shell of the import flow. Call-site tests: the JSON envelope, the
 * 400s, the admin gate on the write path, and the "the server re-validates before saving" rule.
 */
public class OssieImportResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private OssieImportResource resource;

    private static final String LOOKML =
            """
            view: orders {
              source_table: public.orders
              dimension: id { primary_key: yes sql: ${TABLE}.id }
              dimension: amount { sql: ${TABLE}.amount }
              measure: total { type: sum sql: ${TABLE}.amount }
            }
            """;

    @Before
    public void setUp() throws Exception {
        resource = new OssieImportResource();
        Path root = tmp.newFolder("semantic-models").toPath();
        resource.setImportService(new VendorModelImportService(VendorModelConverterRegistry.defaults(), root));
        resource.setUserService(new StubUserService(true));
    }

    @Test
    public void formatsListsTheConverters() {
        Response r = resource.formats();
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<VendorModelConverterRegistry.FormatDescriptor> body =
                (List<VendorModelConverterRegistry.FormatDescriptor>) r.getEntity();
        assertEquals(2, body.size());
        assertTrue(body.stream().anyMatch(d -> "lookml".equals(d.getId())));
    }

    @Test
    public void importJsonReturnsYamlAndReport() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("lookml");
        req.setModelName("Sales");
        OssieImportResource.FilePayload f = new OssieImportResource.FilePayload();
        f.setName("orders.view");
        f.setContent(LOOKML);
        req.setFiles(List.of(f));

        Response r = resource.importJson(req);
        assertEquals(200, r.getStatus());
        VendorModelImportService.ImportResult result = (VendorModelImportService.ImportResult) r.getEntity();
        assertEquals("Sales", result.getModelName());
        assertTrue(result.getYaml().contains("semantic_model:"));
        assertEquals(1, result.getValidation().getDatasetCount());
        assertEquals(1, result.getValidation().getMetricCount());
        assertTrue(result.getValidation().getWarningCount() >= 0);
    }

    @Test
    public void importJsonAcceptsTheSinglePasteShape() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("lookml");
        req.setName("pasted.view");
        req.setContent(LOOKML);
        Response r = resource.importJson(req);
        assertEquals(200, r.getStatus());
    }

    @Test
    public void missingFormatIs400() {
        Response r = resource.importJson(new OssieImportResource.ImportRequest());
        assertEquals(400, r.getStatus());
        assertNotNull(((OssieImportResource.ErrorResponse) r.getEntity()).getError());
    }

    @Test
    public void missingContentIs400() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("lookml");
        assertEquals(400, resource.importJson(req).getStatus());
    }

    @Test
    public void unknownFormatIs400WithSupportedList() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("tableau");
        OssieImportResource.FilePayload f = new OssieImportResource.FilePayload();
        f.setContent("x");
        req.setFiles(List.of(f));
        Response r = resource.importJson(req);
        assertEquals(400, r.getStatus());
        assertTrue(
                ((OssieImportResource.ErrorResponse) r.getEntity()).getError().contains("lookml"));
    }

    @Test
    public void unparseablePayloadIs400() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("dbt");
        OssieImportResource.FilePayload f = new OssieImportResource.FilePayload();
        f.setName("manifest.json");
        f.setContent("not json");
        req.setFiles(List.of(f));
        assertEquals(400, resource.importJson(req).getStatus());
    }

    @Test
    public void savePersistsAndReturnsThePath() {
        VendorModelImportService.ImportResult result =
                (VendorModelImportService.ImportResult) convert().getEntity();

        OssieImportResource.SaveRequest req = new OssieImportResource.SaveRequest();
        req.setModelName(result.getModelName());
        req.setYaml(result.getYaml());
        Response r = resource.save(req);
        assertEquals(200, r.getStatus());
        String path = ((OssieImportResource.SaveResponse) r.getEntity()).getPath();
        assertTrue(path.endsWith("sales.ossie.yaml"));
    }

    @Test
    public void saveRefusesAModelTheServerRejects() {
        // A hand-written model whose relationship points at a dataset that isn't there: the
        // client said nothing, the server decides.
        OssieImportResource.SaveRequest req = new OssieImportResource.SaveRequest();
        req.setModelName("broken");
        req.setYaml(
                """
                version: 0.2.0.dev0
                semantic_model:
                - name: broken
                  datasets:
                  - name: orders
                    source: orders
                    fields:
                    - name: id
                      expression: {dialects: [{dialect: ANSI_SQL, expression: id}]}
                  relationships:
                  - name: orders_to_ghosts
                    from: orders
                    to: ghosts
                    from_columns: [id]
                    to_columns: [id]
                """);
        Response r = resource.save(req);
        assertEquals(400, r.getStatus());
        OssieValidationReport report = ((OssieImportResource.SaveRejected) r.getEntity()).getValidation();
        assertTrue(report.hasErrors());
    }

    @Test
    public void saveIsForbiddenForNonAdmins() {
        resource.setUserService(new StubUserService(false));
        OssieImportResource.SaveRequest req = new OssieImportResource.SaveRequest();
        req.setModelName("sales");
        req.setYaml("version: 0.2.0.dev0\n");
        assertEquals(403, resource.save(req).getStatus());
    }

    @Test
    public void saveWithNoYamlIs400() {
        assertEquals(400, resource.save(new OssieImportResource.SaveRequest()).getStatus());
    }

    private Response convert() {
        OssieImportResource.ImportRequest req = new OssieImportResource.ImportRequest();
        req.setFormat("lookml");
        req.setModelName("Sales");
        OssieImportResource.FilePayload f = new OssieImportResource.FilePayload();
        f.setName("orders.view");
        f.setContent(LOOKML);
        req.setFiles(List.of(f));
        return resource.importJson(req);
    }

    /** Minimal {@link UserService} stand-in — the real one needs a repository + session store. */
    private static class StubUserService extends UserService {
        private final boolean admin;

        StubUserService(boolean admin) {
            this.admin = admin;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }
    }
}
