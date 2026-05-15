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
 * <p>The {@code expression} is raw MDX. Same security/trust model as
 * {@code ThinCalculatedMember.formula} on the legacy surface — agents
 * supplying named sets are trusted with the cube's MDX dialect. Don't
 * accept named sets from untrusted callers without a dedicated
 * validation pass (see saiku#786 for the parallel concern on
 * {@code members[]}).
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
