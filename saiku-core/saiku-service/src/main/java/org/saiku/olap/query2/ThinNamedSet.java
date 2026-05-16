/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Reusable MDX named set on a {@link ThinQueryModel} (saiku#775). Sibling
 * shape to {@link ThinCalculatedMember}: an analyst defines the set once
 * by name + expression, then references it in axis selections.
 *
 * <p>Emitted MDX shape: {@code WITH SET [<name>] AS (<expression>)}
 * prepended to the SELECT clause when the query is materialised.
 *
 * <p>Status note: the Query2-mode (QUERYMODEL) execution path emits MDX
 * via the saiku-query library, which doesn't yet expose a NamedSet API.
 * Persistence of this DTO works today (Jackson round-trip + REST
 * endpoints in {@code Query2Resource}); MDX emission for QUERYMODEL
 * mode lands when saiku-query adds {@code Query.addNamedSet(...)}. The
 * MDX-mode path (AI Query API + raw-MDX submissions) emits the WITH
 * clause directly today via {@code AiSchemaConverter}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThinNamedSet {

    private String name;
    private String expression;
    private String caption;
    private Map<String, String> properties = new HashMap<>();

    public ThinNamedSet() {}

    public ThinNamedSet(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }

    public ThinNamedSet(String name, String expression, String caption, Map<String, String> properties) {
        this.name = name;
        this.expression = expression;
        this.caption = caption;
        this.properties = properties == null ? new HashMap<>() : properties;
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

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new HashMap<>() : properties;
    }
}
