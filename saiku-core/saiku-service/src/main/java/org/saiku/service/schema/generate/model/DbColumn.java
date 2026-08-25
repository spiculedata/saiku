/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.model;

import java.sql.JDBCType;

/**
 * A column in a relational source table, captured in JDBC-neutral form for
 * consumption by the schema-generation pipeline (classifier, dim/measure/time
 * builders, inferrer).
 *
 * <p>Pure value type — no behaviour beyond record accessors.
 */
public record DbColumn(String name, JDBCType type, boolean nullable, boolean primaryKey) {}
