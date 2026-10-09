/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.quickstart;

import java.util.ArrayList;
import java.util.List;
import org.saiku.service.schema.generate.quickstart.QuickstartUploadResult;

/**
 * Wire shape of {@code POST /saiku/admin/quickstart/upload}. Mirrors {@link
 * QuickstartUploadResult} rather than serialising it directly, so the service layer's internal
 * shape can change without moving the REST contract (same separation {@code DraftView} keeps from
 * {@code DraftSchema} in the sibling {@code schemagen} package).
 */
public record QuickstartUploadResponse(
        String jdbcUrl, String driver, String tableName, int rowCount, List<ColumnView> columns) {

    public record ColumnView(String name, String type) {}

    public static QuickstartUploadResponse from(QuickstartUploadResult result) {
        List<ColumnView> columns = new ArrayList<>(result.columns().size());
        result.columns().forEach(c -> columns.add(new ColumnView(c.name(), c.type())));
        return new QuickstartUploadResponse(
                result.jdbcUrl(), result.driver(), result.tableName(), result.rowCount(), columns);
    }
}
