/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

/** One loaded column's name (as created in H2) and inferred {@link CsvTable.ColumnType} name. */
public record QuickstartColumn(String name, String type) {}
