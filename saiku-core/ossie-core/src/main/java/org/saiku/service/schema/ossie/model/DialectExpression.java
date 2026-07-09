/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ossie {@code DialectExpression}. Legal {@code dialect} values are constrained by the spec's
 * {@code Dialect} enum ({@code ANSI_SQL}, {@code SNOWFLAKE}, {@code MDX}, {@code TABLEAU}, {@code
 * DATABRICKS}, {@code MAQL}); we don't validate at the model layer — the converter emits only
 * ANSI_SQL and MDX, and validation lives in the writer round-trip test.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DialectExpression {
    private String dialect;
    private String expression;

    public DialectExpression() {}

    public DialectExpression(String dialect, String expression) {
        this.dialect = dialect;
        this.expression = expression;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String v) {
        this.dialect = v;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String v) {
        this.expression = v;
    }
}
