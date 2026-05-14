/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.schema.generate.session;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.saiku.service.schema.generate.writer.GeneratedSidecarIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link SchemaGenOrchestrator.SidecarStore} that reads the
 * {@code <schemaName>.generated.json} companion file written by
 * {@code SchemaGeneratorResource.datasourceBackedSink(...)}.
 *
 * <p>Lookup strategy:
 *
 * <ol>
 *   <li>Try {@code /datasources/<dataSourceId>.generated.json} directly — covers the common case
 *       where the Saiku data-source name and the generated schema name match.
 *   <li>If that's empty, resolve the data source's Mondrian {@code Catalog=} property to a
 *       {@code /datasources/<name>.xml} path and swap the extension to {@code .generated.json}.
 * </ol>
 *
 * <p>Parse failures are logged and swallowed — a corrupt sidecar must never crash the generation
 * pipeline. Missing files are expected (first-run mode) and are logged at DEBUG only.
 */
public class RepositorySidecarStore implements SchemaGenOrchestrator.SidecarStore {

    private static final Logger LOG = LoggerFactory.getLogger(RepositorySidecarStore.class);

    private static final String DATASOURCES_PREFIX = "/datasources/";
    private static final String SIDECAR_SUFFIX = ".generated.json";
    private static final String MONDRIAN_CATALOG_TOKEN = "Catalog=";
    /** Mondrian catalog URIs look like {@code mondrian:///datasources/<name>.xml}. */
    private static final String MONDRIAN_CATALOG_URI_PREFIX = "mondrian://";

    private final DatasourceService datasourceService;

    public RepositorySidecarStore(DatasourceService datasourceService) {
        this.datasourceService = Objects.requireNonNull(datasourceService, "datasourceService");
    }

    @Override
    public Optional<GeneratedSidecar> load(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.isEmpty()) {
            return Optional.empty();
        }
        // 1. direct lookup by dataSourceId
        Optional<GeneratedSidecar> direct = tryRead(DATASOURCES_PREFIX + dataSourceId + SIDECAR_SUFFIX);
        if (direct.isPresent()) {
            return direct;
        }
        // 2. resolve via the datasource's Mondrian catalog URI
        String schemaName = resolveSchemaNameFromCatalog(dataSourceId);
        if (schemaName != null && !schemaName.equals(dataSourceId)) {
            return tryRead(DATASOURCES_PREFIX + schemaName + SIDECAR_SUFFIX);
        }
        LOG.debug("No sidecar present for data source '{}'", dataSourceId);
        return Optional.empty();
    }

    private Optional<GeneratedSidecar> tryRead(String path) {
        String json;
        try {
            json = datasourceService.getInternalFileData(path);
        } catch (RuntimeException e) {
            LOG.warn("Failed reading sidecar at {}: {}", path, e.getMessage());
            return Optional.empty();
        }
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GeneratedSidecarIo.read(json));
        } catch (RuntimeException e) {
            LOG.warn("Corrupt sidecar at {} ({}) — re-run will proceed as a first-run", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract the {@code <name>} portion of a data source's {@code Catalog=mondrian:///datasources/<name>.xml}
     * URL, or {@code null} if the property isn't present / doesn't match the expected shape.
     */
    private String resolveSchemaNameFromCatalog(String dataSourceId) {
        SaikuDatasource ds;
        try {
            ds = datasourceService.getDatasource(dataSourceId);
        } catch (RuntimeException e) {
            LOG.debug("getDatasource({}) failed: {}", dataSourceId, e.getMessage());
            return null;
        }
        if (ds == null) {
            return null;
        }
        Properties props = ds.getProperties();
        if (props == null) {
            return null;
        }
        String location = props.getProperty("location");
        if (location == null) {
            return null;
        }
        int idx = location.indexOf(MONDRIAN_CATALOG_TOKEN);
        if (idx < 0) {
            return null;
        }
        int start = idx + MONDRIAN_CATALOG_TOKEN.length();
        int end = location.indexOf(';', start);
        String catalog = end < 0 ? location.substring(start) : location.substring(start, end);
        // Strip mondrian:// prefix if present
        if (catalog.startsWith(MONDRIAN_CATALOG_URI_PREFIX)) {
            catalog = catalog.substring(MONDRIAN_CATALOG_URI_PREFIX.length());
            // Remaining form is "/datasources/<name>.xml" (possibly with extra leading slash).
            while (catalog.startsWith("//")) {
                catalog = catalog.substring(1);
            }
        }
        // Accept either "/datasources/name.xml" or plain "name.xml".
        int slash = catalog.lastIndexOf('/');
        String base = slash >= 0 ? catalog.substring(slash + 1) : catalog;
        if (base.endsWith(".xml")) {
            base = base.substring(0, base.length() - 4);
        }
        return base.isEmpty() ? null : base;
    }
}
