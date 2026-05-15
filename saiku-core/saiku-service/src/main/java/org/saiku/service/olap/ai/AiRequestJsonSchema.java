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
        root.put(
                "description",
                "Typed body for POST /saiku/api/ai/query. Every name field must "
                        + "resolve against the AiSchema returned by /ai/schema/{cubeId}. "
                        + "Display names from the enrichment overlay are also valid.");
        root.put("type", "object");
        root.put("required", List.of("cube", "measures"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cube", cubeRef());
        properties.put(
                "measures", arrayOf(measureSelection(), "At least one measure required. Goes on the COLUMNS axis."));
        properties.put(
                "rows", arrayOf(axisSelection(), "Axis entries that go on ROWS. Multiple entries are CROSSJOIN-ed."));
        properties.put(
                "columns",
                arrayOf(axisSelection(), "Additional dimensions on the COLUMNS axis, cross-joined with measures."));
        properties.put("filters", arrayOf(filterSelection(), "Slicer filters — applied in the MDX WHERE clause."));
        properties.put(
                "order",
                arrayOf(
                        orderBy(),
                        "Optional sort. Each entry is {by: measureName, direction: asc|desc}. "
                                + "Combined with limit, emits TopCount/BottomCount; alone, emits Order."));
        properties.put(
                "limit",
                intField(
                        0,
                        "Optional row cap. With order, emits TopCount/BottomCount; without order, emits HEAD(rows, N). 0 or absent = no cap."));
        properties.put(
                "visualTotals",
                boolField(
                        false,
                        "If true, wrap the rows axis in VISUALTOTALS so parent totals reflect only the visible members."));
        properties.put("nonEmpty", boolField(true, "If true (default), strip rows/columns whose every cell is empty."));
        root.put("properties", properties);

        Map<String, Object> exampleRoot = new LinkedHashMap<>();
        exampleRoot.put(
                "cube",
                Map.of(
                        "connectionName", "foodmart",
                        "catalog", "FoodMart",
                        "schema", "FoodMart",
                        "cubeName", "Sales"));
        exampleRoot.put("measures", List.of(Map.of("name", "Store Sales")));
        exampleRoot.put(
                "rows",
                List.of(Map.of(
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
        Map<String, Object> o = obj("Measure on the COLUMNS axis.", props);
        o.put("required", List.of("name"));
        return o;
    }

    private static Map<String, Object> axisSelection() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dimension", stringField("Dimension name (canonical or display)."));
        props.put("hierarchy", stringField("Hierarchy name. Optional when the dimension has only one hierarchy."));
        props.put("level", stringField("Level name within the hierarchy."));
        props.put(
                "members",
                arrayOf(
                        stringField(null),
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
        Map<String, Object> op = stringField("Operator. 'in' (default) — emit the explicit member set. "
                + "'not_in' — emit Except(level.Members, {members}). "
                + "'between' — emit Range(start, end); members must have 2 entries. "
                + "'descendants_of' — emit Descendants(member, ALL); members must have 1 entry. "
                + "'relative' — emit a time-relative set; uses 'value' (and 'n' for last_n_*). 'members' is unused.");
        op.put("enum", List.of("in", "not_in", "between", "descendants_of", "relative"));
        op.put("default", "in");
        props.put("op", op);
        props.put(
                "members",
                arrayOf(
                        stringField(null),
                        "MDX unique-name members. Required for in/not_in (>=1), between (exactly 2), descendants_of (exactly 1). "
                                + "Build them by joining the level's uniqueName with the member name "
                                + "(e.g. [Time].[Time By].[Year].&[1997]), or fetch ready-made via GET /ai/members/search."));
        Map<String, Object> value = stringField("Relative preset (only when op=relative). "
                + "last_n_days/months/quarters/years pick the most-recent N members on the level. "
                + "ytd/mtd/qtd use Mondrian's Ytd()/Mtd()/Qtd() against the time dimension's default member. "
                + "previous_period picks the member preceding the latest available member in the cube "
                + "(NOT relative to wall-clock time — if the warehouse is stale, this is stale).");
        value.put(
                "enum",
                List.of(
                        "last_n_days",
                        "last_n_months",
                        "last_n_quarters",
                        "last_n_years",
                        "ytd",
                        "mtd",
                        "qtd",
                        "previous_period"));
        props.put("value", value);
        props.put("n", intField(1, "Count for the last_n_* presets. Defaults to 1; ignored for other presets."));
        Map<String, Object> o = obj("Slicer filter — lands in the WHERE clause.", props);
        // members is only required when op∈{in, not_in, between, descendants_of}; relative ops
        // use value+n. Marking "members" required at the schema level would over-constrain
        // the relative case, so the document-level required list is dimension+level only.
        o.put("required", List.of("dimension", "level"));
        return o;
    }

    private static Map<String, Object> orderBy() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("by", stringField("Measure name to sort by. Must match one of the request's measures."));
        Map<String, Object> dir = stringField("Sort direction. Default desc.");
        dir.put("enum", List.of("asc", "desc"));
        dir.put("default", "desc");
        props.put("direction", dir);
        Map<String, Object> o = obj("Sort entry. With a non-zero limit, emits TopCount/BottomCount.", props);
        o.put("required", List.of("by"));
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
