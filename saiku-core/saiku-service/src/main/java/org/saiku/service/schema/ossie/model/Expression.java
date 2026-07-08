/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Ossie {@code Expression} — wraps one or more {@link DialectExpression}s. Every field / metric
 * carries at least one dialect entry. Saiku's exporter emits ANSI_SQL and MDX for every measure by
 * default so consumers can pick either at query time.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Expression {
    private List<DialectExpression> dialects = new ArrayList<>();

    public List<DialectExpression> getDialects() {
        return dialects;
    }

    public void setDialects(List<DialectExpression> v) {
        this.dialects = v == null ? new ArrayList<>() : v;
    }

    public Expression add(String dialect, String expression) {
        this.dialects.add(new DialectExpression(dialect, expression));
        return this;
    }

    public static Expression ansi(String sql) {
        return new Expression().add("ANSI_SQL", sql);
    }
}
