/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.schema.generate.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.GeneratedSidecarIo;

/**
 * Unit tests for {@link RepositorySidecarStore}: covers the three branches — present-and-parses,
 * absent, and corrupt-returns-empty — plus the fallback path that resolves a mismatched schema
 * name via the Mondrian {@code Catalog=} property.
 */
public class RepositorySidecarStoreTest {

    @Test
    public void load_missingFile_returnsEmpty() {
        StubDatasourceService ds = new StubDatasourceService();
        RepositorySidecarStore store = new RepositorySidecarStore(ds);

        assertFalse(store.load("orders").isPresent());
    }

    @Test
    public void load_directMatch_parsesSidecar() {
        StubDatasourceService ds = new StubDatasourceService();
        DraftSchema draft = new DraftSchema("orders");
        String json = GeneratedSidecarIo.write(GeneratedSidecarIo.build(draft, List.of(), "orders", "4.x-test"));
        ds.files.put("/datasources/orders.generated.json", json);

        Optional<GeneratedSidecar> loaded = new RepositorySidecarStore(ds).load("orders");
        assertTrue(loaded.isPresent());
        assertEquals("orders", loaded.get().schemaName());
    }

    @Test
    public void load_emptyStringTreatedAsAbsent() {
        // Matches FilesystemRepositoryManager.getInternalFile — missing files come back as "".
        StubDatasourceService ds = new StubDatasourceService();
        ds.files.put("/datasources/orders.generated.json", "");

        assertFalse(new RepositorySidecarStore(ds).load("orders").isPresent());
    }

    @Test
    public void load_corruptJson_returnsEmptyAndDoesNotThrow() {
        StubDatasourceService ds = new StubDatasourceService();
        ds.files.put("/datasources/orders.generated.json", "{this is not: valid json");

        Optional<GeneratedSidecar> loaded = new RepositorySidecarStore(ds).load("orders");
        assertFalse(loaded.isPresent());
    }

    @Test
    public void load_fallsBackToCatalogResolution_whenDataSourceIdDiffersFromSchemaName() {
        StubDatasourceService ds = new StubDatasourceService();
        // Data source "ds-id" but the catalog points at a schema named "warehouse"
        Properties props = new Properties();
        props.setProperty(
                "location",
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:;Catalog=mondrian:///datasources/warehouse.xml;JdbcDrivers=org.h2.Driver");
        ds.datasources.put("ds-id", new SaikuDatasource("ds-id", SaikuDatasource.Type.OLAP, props));

        DraftSchema draft = new DraftSchema("warehouse");
        String json = GeneratedSidecarIo.write(GeneratedSidecarIo.build(draft, List.of(), "warehouse", "4.x-test"));
        ds.files.put("/datasources/warehouse.generated.json", json);

        Optional<GeneratedSidecar> loaded = new RepositorySidecarStore(ds).load("ds-id");
        assertTrue(loaded.isPresent());
        assertEquals("warehouse", loaded.get().schemaName());
    }

    @Test
    public void load_nullOrEmptyId_returnsEmpty() {
        RepositorySidecarStore store = new RepositorySidecarStore(new StubDatasourceService());
        assertFalse(store.load(null).isPresent());
        assertFalse(store.load("").isPresent());
    }

    /**
     * {@link DatasourceService} test double. Overrides only the two methods the store touches.
     * We extend rather than mock so we stay off Mockito-on-classes bookkeeping.
     */
    private static final class StubDatasourceService extends DatasourceService {
        final Map<String, String> files = new HashMap<>();
        final Map<String, SaikuDatasource> datasources = new HashMap<>();

        @Override
        public String getInternalFileData(String path) {
            return files.get(path);
        }

        @Override
        public SaikuDatasource getDatasource(String name) {
            return datasources.get(name);
        }
    }
}
