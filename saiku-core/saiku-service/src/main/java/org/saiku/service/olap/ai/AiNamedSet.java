/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Named set entry on an {@link AiQueryRequest}. Emits as
 * {@code WITH SET [<name>] AS (<expression>)} ahead of the SELECT
 * clause, then becomes available as a member-set reference inside
 * {@code rows}, {@code columns}, or {@code filters}.
 *
 * <p>The {@code name} is a validated identifier (letters, digits, spaces,
 * underscores and hyphens, at most 64 characters, optionally bracketed).
 * The {@code expression} is an MDX set expression, validated by
 * {@code AiSchemaConverter.validateNamedSetExpression} (saiku#2541)
 * before it is concatenated into {@code WITH SET [<name>] AS (...)}:
 * statement keywords ({@code MEMBER}, {@code SELECT}, {@code WITH}, ...),
 * MDX comments, StrTo* functions and unbalanced delimiters are refused; every reference
 * must resolve against the cube's dimensions, the {@code [Measures]}
 * hierarchy or an earlier named set of the same request; and a reference
 * to a hierarchy containing a PII level or a PII measure is refused
 * (saiku#1918). The legacy trust model
 * ("expression bodies passed to Mondrian unmodified") does not apply
 * on the AI surface.
 *
 * <p>Two named sets cannot share a name within one request; the
 * validator rejects duplicates before they reach Mondrian.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AiNamedSet {

    private String name;
    private String expression;

    public AiNamedSet() {}

    public AiNamedSet(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }
}
