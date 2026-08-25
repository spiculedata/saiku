/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.model;

/**
 * A single-column foreign-key edge from this table's {@code fromColumn} to
 * {@code toTable}.{@code toColumn}. Composite keys are represented as multiple
 * {@link DbForeignKey} entries on the same table.
 *
 * <p>Pure value type.
 */
public record DbForeignKey(String fromColumn, String toTable, String toColumn) {}
