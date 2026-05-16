/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level body for {@code POST /saiku/api/ai/query}. Designed so an
 * agent can fill it in by reading {@code GET /ai/schema/{cubeId}} —
 * every name field must resolve against that schema.
 *
 * <p>The {@code cube} field accepts either:
 * <ul>
 *   <li>a structured {@link AiCubeRef} object, OR
 *   <li>a string of the form {@code "connection/catalog/schema/cubeName"}
 *       (matching the cubeId returned by {@code /ai/cubes}).
 * </ul>
 * Internally we always resolve to an {@link AiCubeRef}. Agents reading
 * the cube reference back can rely on {@link #getCube()}.
 */
public class AiQueryRequest {

    private AiCubeRef cube;
    private List<AiMeasureSelection> measures = new ArrayList<>();
    private List<AiAxisSelection> rows = new ArrayList<>();
    private List<AiAxisSelection> columns = new ArrayList<>();
    private List<AiFilterSelection> filters = new ArrayList<>();
    /** Optional sort + top-N order. {@code by} is a measure name. With a
     *  non-zero {@link #limit}, emits TopCount/BottomCount; without limit,
     *  emits Order. */
    private List<AiOrderBy> order = new ArrayList<>();
    /** Optional row cap. With {@link #order}, emits TopCount/BottomCount;
     *  without order, emits HEAD(rows, N). <=0 means "no cap". */
    private int limit = 0;
    /** Optional: include parent totals via VISUALTOTALS on rows. */
    private boolean visualTotals = false;
    /** Optional: drop entirely-empty rows from the result (NON EMPTY). */
    private boolean nonEmpty = true;
    /** Optional MDX named sets — emitted as {@code WITH SET [name] AS (expr)}
     *  before SELECT (saiku#775). Useful for reusable member collections
     *  ("top 50 customers by revenue") referenced multiple times in the
     *  same query. */
    private List<AiNamedSet> namedSets = new ArrayList<>();

    /** Programmatic setter used from Java code paths (resource handlers, tests). */
    public void setCube(AiCubeRef v) {
        this.cube = v;
    }

    /**
     * Polymorphic {@code cube} field deserialiser. Accepts either:
     * <ul>
     *   <li>a JSON object — bound to {@link AiCubeRef}, OR</li>
     *   <li>a string of the form {@code "connection/catalog/schema/cubeName"}
     *       (matching the cubeId returned by {@code /ai/cubes}).</li>
     * </ul>
     * We bind to {@link JsonNode} and branch on shape rather than relying on
     * {@code @JsonAnySetter}, which only fires for unknown fields and would
     * miss the case where a typed setter already exists.
     */
    @JsonSetter("cube")
    public void setCubeFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            this.cube = null;
            return;
        }
        if (node.isTextual()) {
            String[] parts = node.asText().split("/", -1);
            if (parts.length == 4) {
                for (String p : parts) if (p.isEmpty()) return;
                this.cube = new AiCubeRef(parts[0], parts[1], parts[2], parts[3]);
            }
            return;
        }
        if (node.isObject()) {
            this.cube = new AiCubeRef(
                    textOrNull(node, "connectionName"),
                    textOrNull(node, "catalog"),
                    textOrNull(node, "schema"),
                    textOrNull(node, "cubeName"));
        }
    }

    private static String textOrNull(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public AiCubeRef getCube() {
        return cube;
    }

    public List<AiMeasureSelection> getMeasures() {
        return measures;
    }

    public void setMeasures(List<AiMeasureSelection> v) {
        this.measures = v == null ? new ArrayList<>() : v;
    }

    public List<AiAxisSelection> getRows() {
        return rows;
    }

    public void setRows(List<AiAxisSelection> v) {
        this.rows = v == null ? new ArrayList<>() : v;
    }

    public List<AiAxisSelection> getColumns() {
        return columns;
    }

    public void setColumns(List<AiAxisSelection> v) {
        this.columns = v == null ? new ArrayList<>() : v;
    }

    public List<AiFilterSelection> getFilters() {
        return filters;
    }

    public void setFilters(List<AiFilterSelection> v) {
        this.filters = v == null ? new ArrayList<>() : v;
    }

    public List<AiOrderBy> getOrder() {
        return order;
    }

    public void setOrder(List<AiOrderBy> v) {
        this.order = v == null ? new ArrayList<>() : v;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int v) {
        this.limit = v;
    }

    public boolean isVisualTotals() {
        return visualTotals;
    }

    public void setVisualTotals(boolean v) {
        this.visualTotals = v;
    }

    public boolean isNonEmpty() {
        return nonEmpty;
    }

    public void setNonEmpty(boolean v) {
        this.nonEmpty = v;
    }

    public List<AiNamedSet> getNamedSets() {
        return namedSets;
    }

    public void setNamedSets(List<AiNamedSet> v) {
        this.namedSets = v == null ? new ArrayList<>() : v;
    }
}
