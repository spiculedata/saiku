/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#1730 — the import service: registry dispatch, the YAML round trip that proves what we
 * hand the datasource will load, the guard rails, and the write-on-confirm save.
 */
public class VendorModelImportServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VendorModelImportService service;

    private static final String LOOKML =
            """
            explore: orders {
              from: orders
              join: customers {
                from: customers
                join_on: ${orders.customer_id} = ${customers.id} ;;
              }
            }
            view: orders {
              source_table: public.orders
              dimension: id { primary_key: yes sql: ${TABLE}.id }
              dimension: customer_id { sql: ${TABLE}.customer_id }
              measure: total_amount { type: sum sql: ${TABLE}.amount }
            }
            view: customers {
              source_table: public.customers
              dimension: id { primary_key: yes sql: ${TABLE}.id }
            }
            """;

    @Before
    public void setUp() throws Exception {
        service = new VendorModelImportService(
                VendorModelConverterRegistry.defaults(),
                tmp.newFolder("semantic-models").toPath());
    }

    @Test
    public void supportedFormatsCoverLookmlAndDbt() {
        List<String> ids = service.supportedFormats().stream()
                .map(VendorModelConverterRegistry.FormatDescriptor::getId)
                .toList();
        assertTrue(ids.contains("lookml"));
        assertTrue(ids.contains("dbt"));
        for (VendorModelConverterRegistry.FormatDescriptor d : service.supportedFormats()) {
            assertNotNull(d.getDisplayName());
            assertFalse(d.getDescription().isBlank());
        }
    }

    @Test
    public void importProducesParseableYamlAndAWarningFreeReport() throws Exception {
        VendorModelImportService.ImportResult r =
                service.importModel("lookml", VendorModelFile.single("orders.view", LOOKML), "Sales");
        assertEquals("lookml", r.getFormatId());
        assertEquals("Sales", r.getModelName());
        assertTrue(r.getYaml().contains("semantic_model:"));
        assertFalse(r.getValidation().hasErrors());
        assertEquals(2, r.getValidation().getDatasetCount());
        assertEquals(3, r.getValidation().getFieldCount());
        assertEquals(1, r.getValidation().getMetricCount());
        assertEquals(1, r.getValidation().getRelationshipCount());
        // The exact YAML the preview showed is the YAML that round-trips.
        assertFalse(service.validateYaml(r.getYaml()).hasErrors());
    }

    @Test
    public void unknownFormatIsRejectedWithTheSupportedList() throws Exception {
        try {
            service.importModel("tableau", VendorModelFile.single("x", "y"), null);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("lookml"));
            assertTrue(e.getMessage().contains("dbt"));
        }
    }

    @Test
    public void noFilesIsRejected() throws Exception {
        try {
            service.importModel("lookml", new ArrayList<>(), null);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("No file"));
        }
    }

    @Test
    public void blankUploadIsRejected() throws Exception {
        try {
            service.importModel("lookml", VendorModelFile.single("x.view", "   \n"), null);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void tooManyFilesIsRejected() throws Exception {
        List<VendorModelFile> files = new ArrayList<>();
        for (int i = 0; i <= VendorModelImportService.MAX_FILES; i++) {
            files.add(new VendorModelFile("f" + i + ".view", "view: v" + i + " { dimension: id { sql: id } }"));
        }
        try {
            service.importModel("lookml", files, null);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Too many"));
        }
    }

    @Test
    public void oversizedUploadIsRejected() throws Exception {
        StringBuilder big = new StringBuilder("view: v { dimension: id { sql: ");
        big.append("x".repeat(VendorModelImportService.MAX_TOTAL_BYTES + 16));
        big.append(" } }");
        try {
            service.importModel("lookml", VendorModelFile.single("big.view", big.toString()), null);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("limit"));
        }
    }

    @Test
    public void saveWritesUnderTheConfiguredRootAndRefusesToClobber() throws Exception {
        VendorModelImportService.ImportResult r =
                service.importModel("lookml", VendorModelFile.single("orders.view", LOOKML), "Sales Model");
        String path = service.save(r.getModelName(), r.getYaml(), false);
        assertTrue(path.endsWith("sales-model.ossie.yaml"));
        String written = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        assertTrue(written.contains("semantic_model:"));
        assertTrue(written.startsWith("# Imported from a vendor semantic model by Saiku"));

        try {
            service.save(r.getModelName(), r.getYaml(), false);
            fail("expected the second save to be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }
        // Overwrite is explicit.
        assertEquals(path, service.save(r.getModelName(), r.getYaml(), true));
    }

    @Test
    public void saveRejectsBlankYaml() {
        try {
            service.save("m", "  ", true);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Nothing to save"));
        }
    }

    @Test
    public void slugIsFilesystemSafe() {
        assertEquals("orders", VendorModelImportService.slug("orders"));
        assertEquals("sales-model", VendorModelImportService.slug("../../Sales Model"));
        assertEquals("model", VendorModelImportService.slug(".."));
        assertEquals("model", VendorModelImportService.slug("///"));
    }

    @Test
    public void validateYamlFlagsADocumentWithNoSemanticModel() {
        OssieValidationReport report = service.validateYaml("just: a mapping\n");
        assertTrue(report.hasErrors());
        assertEquals("NO_SEMANTIC_MODEL", report.getDiagnostics().get(0).getCode());
    }

    @Test
    public void validateYamlRejectsUnparseableYaml() {
        try {
            service.validateYaml("\t- : [ unclosed\n");
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
    }
}
