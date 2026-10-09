/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

import java.util.List;

/**
 * Result of {@link QuickstartIngestService#ingest}: everything the REST layer needs to hand back
 * to the client so it can register the loaded table as a Saiku datasource itself via the existing
 * {@code /admin/datasources} endpoint — this service only ever touches the embedded H2 database,
 * never the datasource repository.
 *
 * @param jdbcUrl raw {@code jdbc:h2:...} URL (not Mondrian-wrapped) for the freshly created,
 *     single-table database
 * @param driver JDBC driver class name ({@code org.h2.Driver})
 * @param tableName the sanitised table name actually created
 * @param rowCount number of data rows loaded
 * @param columns the loaded columns, in table-definition order (excludes the surrogate {@code ID}
 *     primary key column)
 */
public record QuickstartUploadResult(
        String jdbcUrl, String driver, String tableName, int rowCount, List<QuickstartColumn> columns) {}
