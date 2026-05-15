/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON Schema (draft 2020-12) describing the shape of
 * {@link AiQueryRequest}. Embedded once per cube schema response so an
 * LLM can self-validate its request body without a round-trip.
 *
 * <p>Maintained by hand on purpose: a runtime Jackson-schema generator
 * would pull in a heavy dependency for a tiny payload and would produce
 * weaker documentation (no descriptions). The fields here are public
 * API; any change to {@link AiQueryRequest} must also be reflected here.
 */
public final class AiRequestJsonSchema {

    private AiRequestJsonSchema() {}

    public static Map<String, Object> forRequest() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", "AiQueryRequest");
        root.put("description",
                "Typed body for POST /saiku/api/ai/query. Every name field must "
                + "resolve against the AiSchema returned by /ai/schema/{cubeId}. "
                + "Display names from the enrichment overlay are also valid.");
        root.put("type", "object");
        root.put("required", List.of("cube", "measures"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cube", cubeRef());
        properties.put("measures", arrayOf(measureSelection(),
                "At least one measure required. Goes on the COLUMNS axis."));
        properties.put("rows", arrayOf(axisSelection(),
                "Axis entries that go on ROWS. Multiple entries are CROSSJOIN-ed."));
        properties.put("columns", arrayOf(axisSelection(),
                "Additional dimensions on the COLUMNS axis, cross-joined with measures."));
        properties.put("filters", arrayOf(filterSelection(),
                "Slicer filters — applied in the MDX WHERE clause."));
        properties.put("limit", intField(0,
                "Optional row cap. Translated into HEAD(rows, N). 0 or absent = no cap."));
        properties.put("visualTotals", boolField(false,
                "If true, wrap the rows axis in VISUALTOTALS so parent totals reflect only the visible members."));
        properties.put("nonEmpty", boolField(true,
                "If true (default), strip rows/columns whose every cell is empty."));
        root.put("properties", properties);

        Map<String, Object> exampleRoot = new LinkedHashMap<>();
        exampleRoot.put("cube", Map.of(
                "connectionName", "foodmart",
                "catalog", "FoodMart",
                "schema", "FoodMart",
                "cubeName", "Sales"));
        exampleRoot.put("measures", List.of(Map.of("name", "Store Sales")));
        exampleRoot.put("rows", List.of(Map.of(
                "dimension", "Time",
                "hierarchy", "Time By",
                "level", "Year")));
        root.put("example", exampleRoot);

        return root;
    }

    private static Map<String, Object> cubeRef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("connectionName", stringField("Connection name from /ai/cubes."));
        props.put("catalog", stringField("Catalog name."));
        props.put("schema", stringField("Schema name."));
        props.put("cubeName", stringField("Cube name."));
        Map<String, Object> o = obj("Cube reference. All four fields required.", props);
        o.put("required", List.of("connectionName", "catalog", "schema", "cubeName"));
        return o;
    }

    private static Map<String, Object> measureSelection() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", stringField("Measure name (canonical or display)."));
        props.put("aggregators", arrayOf(stringField(null),
                "Optional aggregator overrides (e.g. SUM, AVG). Phase 3 hint."));
        Map<String, Object> o = obj("Measure on the COLUMNS axis.", props);
        o.put("required", List.of("name"));
        return o;
    }

    private static Map<String, Object> axisSelection() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dimension", stringField("Dimension name (canonical or display)."));
        props.put("hierarchy", stringField("Hierarchy name. Optional when the dimension has only one hierarchy."));
        props.put("level", stringField("Level name within the hierarchy."));
        props.put("members", arrayOf(stringField(null),
                "Optional explicit MDX unique-name list. If absent, all members at the level are included."));
        Map<String, Object> o = obj("A row or column axis entry.", props);
        o.put("required", List.of("dimension", "level"));
        return o;
    }

    private static Map<String, Object> filterSelection() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dimension", stringField("Dimension name."));
        props.put("hierarchy", stringField("Hierarchy name. Optional when dim has one hierarchy."));
        props.put("level", stringField("Level name."));
        props.put("members", arrayOf(stringField(null),
                "Required: at least one MDX unique-name member to slice by."));
        Map<String, Object> o = obj("Slicer filter — lands in the WHERE clause.", props);
        o.put("required", List.of("dimension", "level", "members"));
        return o;
    }

    private static Map<String, Object> obj(String description, Map<String, Object> properties) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        if (description != null) o.put("description", description);
        o.put("properties", properties);
        return o;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items, String description) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "array");
        if (description != null) a.put("description", description);
        a.put("items", items);
        return a;
    }

    private static Map<String, Object> stringField(String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "string");
        if (description != null) f.put("description", description);
        return f;
    }

    private static Map<String, Object> intField(int defaultValue, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "integer");
        f.put("default", defaultValue);
        if (description != null) f.put("description", description);
        return f;
    }

    private static Map<String, Object> boolField(boolean defaultValue, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "boolean");
        f.put("default", defaultValue);
        if (description != null) f.put("description", description);
        return f;
    }
}
