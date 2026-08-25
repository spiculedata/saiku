/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;

/**
 * Unit coverage for {@link EmbedPiiInspector} (saiku-cloud#940). Drives the
 * fail-CLOSED contract on the saved-query (.saiku/ThinQuery JSON) +
 * dashboard (.saikudash JSON) parse paths, the cube-level "any PII"
 * detection, and the dedupe across multi-tile dashboards.
 */
public class EmbedPiiInspectorTest {

    private StubDatasourceService ds;
    private StubCubeMetadataService cubes;
    private EmbedPiiInspector inspector;

    @Before
    public void setUp() {
        ds = new StubDatasourceService();
        cubes = new StubCubeMetadataService();
        inspector = new EmbedPiiInspector(ds, cubes);
    }

    /* ---------------------------- queries ---------------------------- */

    @Test
    public void query_with_clean_cube_returns_no_pii() {
        // ThinQuery JSON with a cube whose schema has no PII annotations.
        ds.put("/clean.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Sales"));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("query", "/clean.saiku", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals(1, r.cubeIds.size());
        assertFalse(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_pii_measure_returns_pii() {
        ds.put("/dirty.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Customer"));
        AiSchema schema = cleanSchema();
        AiSchema.Measure m = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        m.pii = true;
        schema.measures.put(AiSchema.key("SSN"), m);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/dirty.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void query_with_pii_level_returns_pii() {
        ds.put("/dirty.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Customer"));
        AiSchema schema = cleanSchema();
        AiSchema.Dimension d = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customer", "[Customer]");
        AiSchema.Level l = new AiSchema.Level("Email", "[Customer].[Email]");
        l.pii = true;
        h.levels.put(AiSchema.key("Email"), l);
        d.hierarchies.put(AiSchema.key("Customer"), h);
        schema.dimensions.put(AiSchema.key("Customer"), d);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/dirty.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void query_with_unreadable_file_fails_closed() {
        // Path doesn't resolve in the stub — datasourceService.getFileData
        // throws. Inspector must default to "PII present" so the embed
        // gate refuses rather than allows.
        ds.throwOn("/missing.saiku");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/missing.saiku", "admin", List.of());
        assertTrue("file unreadable must fail closed", r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_garbage_json_fails_closed() {
        ds.put("/broken.saiku", "not json");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/broken.saiku", "admin", List.of());
        assertTrue("unparseable resource must fail closed", r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void query_with_no_cube_fails_closed() {
        // MDX-mode ThinQuery is bound only to a connection — we can't
        // inspect a cube we don't have. Fail closed.
        ds.put("/no-cube.saiku", "{\"name\":\"q\",\"type\":\"MDX\"}");
        EmbedPiiInspector.Result r = inspector.inspect("query", "/no-cube.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    /* -------------------------- dashboards -------------------------- */

    @Test
    public void dashboard_with_clean_tiles_returns_no_pii() {
        ds.put(
                "/clean.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Inventory"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.putSchema("conn-1/Cat/Sch/Inventory", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/clean.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals("both cubes recorded for audit", 2, r.cubeIds.size());
    }

    @Test
    public void dashboard_with_one_pii_tile_returns_pii_but_still_walks_rest() {
        // One PII cube among multiple. The inspector must (a) return PII,
        // (b) still collect the FULL cube set for audit so an admin can
        // see "which of my 3 cubes is the problem."
        ds.put(
                "/mixed.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"),
                        tileJson("conn-1", "Cat", "Sch", "CustomerPii"),
                        tileJson("conn-1", "Cat", "Sch", "Inventory"))));
        AiSchema dirty = cleanSchema();
        AiSchema.Measure ssn = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        ssn.pii = true;
        dirty.measures.put(AiSchema.key("SSN"), ssn);
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.putSchema("conn-1/Cat/Sch/CustomerPii", dirty);
        cubes.putSchema("conn-1/Cat/Sch/Inventory", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/mixed.saikudash", "admin", List.of());
        assertTrue(r.referencesPii);
        assertEquals(3, r.cubeIds.size());
        assertTrue(r.cubeIds.stream().anyMatch(c -> c.endsWith("/CustomerPii")));
    }

    @Test
    public void dashboard_dedupes_cubes_across_tiles() {
        // Two tiles bind the SAME cube — the inspector should only schema-
        // lookup once and the cubeIds list should have one entry.
        ds.put(
                "/dupe.saikudash",
                dashboardJson(
                        List.of(tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Sales"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/dupe.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertEquals(1, r.cubeIds.size());
        assertEquals(1, cubes.lookups);
    }

    @Test
    public void dashboard_with_text_only_tiles_returns_no_pii() {
        // No cube-bound tiles at all — the dashboard is just text /
        // markdown. Nothing to inspect; the result must be permissive
        // because there IS no PII column anywhere.
        ds.put(
                "/text.saikudash",
                "{\"id\":\"d\",\"name\":\"text\",\"version\":1,\"layout\":{\"cols\":12,\"tiles\":[]}}");
        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/text.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
        assertTrue(r.cubeIds.isEmpty());
    }

    @Test
    public void dashboard_with_one_cube_schema_lookup_failure_fails_closed_for_that_cube() {
        // Schema lookup throws for one cube. Treated as PII (fail-closed)
        // so the embed gate refuses rather than allows a possibly-PII
        // cube we can't inspect.
        ds.put(
                "/partial.saikudash",
                dashboardJson(List.of(
                        tileJson("conn-1", "Cat", "Sch", "Sales"), tileJson("conn-1", "Cat", "Sch", "Broken"))));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cleanSchema());
        cubes.throwOn("conn-1/Cat/Sch/Broken");

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/partial.saikudash", "admin", List.of());
        assertTrue("unresolvable cube must fail closed", r.referencesPii);
    }

    /* --------------------- per-column (saiku-cloud#949) --------------------- */

    @Test
    public void query_referencing_only_clean_levels_on_dirty_cube_is_not_pii() {
        // Cube has a PII level "Email" under [Customer].[Customers], but the
        // saved query touches only [Time].[Quarter] × [Product].[Family].
        // v1 would have returned true (the cube has PII somewhere); the
        // per-column refinement must return false.
        ds.put(
                "/clean-axes.saiku",
                thinQueryWithAxes(
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Sales",
                        List.of(
                                new LevelRef("Time", "[Time].[Time]", "Quarter"),
                                new LevelRef("Product", "[Product].[Product]", "Family")),
                        List.of("Store Sales")));
        AiSchema schema = cleanSchema();
        // PII column lives on a totally different dim/hier/level.
        AiSchema.Dimension cust = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy custHier = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        AiSchema.Level email = new AiSchema.Level("Email", "[Customer].[Customers].[Email]");
        email.pii = true;
        custHier.levels.put(AiSchema.key("Email"), email);
        cust.hierarchies.put(AiSchema.key("Customers"), custHier);
        schema.dimensions.put(AiSchema.key("Customer"), cust);
        // Add clean dims/hiers/levels for what the query touches.
        addClean(schema, "Time", "Time", "Quarter");
        addClean(schema, "Product", "Product", "Family");
        addCleanMeasure(schema, "Store Sales");
        cubes.putSchema("conn-1/Cat/Sch/Sales", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/clean-axes.saiku", "admin", List.of());
        assertFalse("query touches only clean columns — must NOT be PII", r.referencesPii);
    }

    @Test
    public void query_referencing_a_pii_level_on_dirty_cube_is_pii() {
        // Same cube as above but this query DOES touch the PII column.
        // Per-column refinement must agree with v1's "yes, PII."
        ds.put(
                "/touches-pii.saiku",
                thinQueryWithAxes(
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Customer",
                        List.of(
                                new LevelRef("Customer", "[Customer].[Customers]", "Email"),
                                new LevelRef("Time", "[Time].[Time]", "Quarter")),
                        List.of("Headcount")));
        AiSchema schema = cleanSchema();
        AiSchema.Dimension cust = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy custHier = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        AiSchema.Level email = new AiSchema.Level("Email", "[Customer].[Customers].[Email]");
        email.pii = true;
        custHier.levels.put(AiSchema.key("Email"), email);
        cust.hierarchies.put(AiSchema.key("Customers"), custHier);
        schema.dimensions.put(AiSchema.key("Customer"), cust);
        addClean(schema, "Time", "Time", "Quarter");
        addCleanMeasure(schema, "Headcount");
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/touches-pii.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void query_referencing_a_pii_measure_is_pii() {
        // Measure is the PII source. Per-column scan must catch it.
        ds.put(
                "/pii-measure.saiku",
                thinQueryWithAxes(
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Customer",
                        List.of(new LevelRef("Time", "[Time].[Time]", "Quarter")),
                        List.of("SSN Count")));
        AiSchema schema = cleanSchema();
        addClean(schema, "Time", "Time", "Quarter");
        AiSchema.Measure ssn = new AiSchema.Measure("SSN Count", "[Measures].[SSN Count]");
        ssn.pii = true;
        schema.measures.put(AiSchema.key("SSN Count"), ssn);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/pii-measure.saiku", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    // saiku-cloud#949 residual: SEC flagged that a per-column FALSE NEGATIVE
    // would slip a PII-projecting query past grantPublic (HTTP 400 gate) if
    // the query→refs extraction (extractRefsFromThinQuery) and the schema PII
    // lookup (refsHitPii) ever disagreed on key derivation. Both route through
    // AiSchema.key(...). The next two tests pin that alignment on the COLUMNS
    // axis (the existing pair above only exercises ROWS) using ONE shared cube
    // schema: the positive proves a PII level projected on a real axis IS
    // detected; the same-schema negative proves the check is genuinely
    // per-column (not cube-wide) — a key-derivation mismatch would flip one.

    @Test
    public void projectedPiiLevel_isDetected() {
        // Core guard: a query that PROJECTS a pii=true level (here on COLUMNS)
        // MUST yield referencesPii=true. If key derivation on the two sides
        // drifted, the projected Email ref would miss the schema's PII flag
        // and this would silently go false — the exact false-negative SEC
        // worried publicly-grants a PII query.
        ds.put(
                "/projects-pii.saiku",
                thinQueryWithAxes(
                        "COLUMNS",
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Sales",
                        List.of(new LevelRef("Customer", "[Customer].[Customers]", "Email")),
                        List.of("Store Sales")));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cubeWithPiiEmailLevel());

        EmbedPiiInspector.Result r = inspector.inspect("query", "/projects-pii.saiku", "admin", List.of());
        assertTrue("PII level projected on COLUMNS must be detected", r.referencesPii);
    }

    @Test
    public void onlyNonPiiLevelsProjected_notFlagged() {
        // Per-column proof on the SAME cube schema as projectedPiiLevel_isDetected:
        // the cube HAS a pii=true Email level, but this query projects only the
        // clean Time.Quarter × Product.Family levels. Result must be false —
        // proving the check is per-column, not cube-wide, AND that the clean
        // refs' keys align (a misalignment could spuriously match, or fail to
        // exclude, the PII level).
        ds.put(
                "/projects-clean.saiku",
                thinQueryWithAxes(
                        "COLUMNS",
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Sales",
                        List.of(
                                new LevelRef("Time", "[Time].[Time]", "Quarter"),
                                new LevelRef("Product", "[Product].[Product]", "Family")),
                        List.of("Store Sales")));
        cubes.putSchema("conn-1/Cat/Sch/Sales", cubeWithPiiEmailLevel());

        EmbedPiiInspector.Result r = inspector.inspect("query", "/projects-clean.saiku", "admin", List.of());
        assertFalse("only clean levels projected — must NOT be flagged", r.referencesPii);
    }

    @Test
    public void query_with_no_query_model_falls_back_to_cube_level_scan() {
        // No queryModel means we can't enumerate columns — must fall back
        // to the v1 conservative cube-level scan. Cube has PII → result
        // is true.
        ds.put("/no-model.saiku", thinQueryJson("conn-1", "Cat", "Sch", "Customer"));
        AiSchema schema = cleanSchema();
        AiSchema.Measure ssn = new AiSchema.Measure("SSN", "[Measures].[SSN]");
        ssn.pii = true;
        schema.measures.put(AiSchema.key("SSN"), ssn);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("query", "/no-model.saiku", "admin", List.of());
        assertTrue("MDX-mode query on dirty cube must fall back to cube-level", r.referencesPii);
    }

    @Test
    public void dashboard_reference_tile_recurses_into_saved_query() {
        // The tile is a reference to a saved query at /tile.saiku. The
        // reference's cube is different from the tile's nominal cube ref
        // (which is null for reference-style tiles), so the inspector must
        // read the file and inspect THAT cube's schema. Saved query is
        // clean — the dashboard should be clean too.
        ds.put("/dash.saikudash", dashboardJson(List.of(referenceTileJson("/tile.saiku"))));
        ds.put(
                "/tile.saiku",
                thinQueryWithAxes(
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Sales",
                        List.of(new LevelRef("Time", "[Time].[Time]", "Quarter")),
                        List.of("Units")));
        AiSchema schema = cleanSchema();
        addClean(schema, "Time", "Time", "Quarter");
        addCleanMeasure(schema, "Units");
        cubes.putSchema("conn-1/Cat/Sch/Sales", schema);

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/dash.saikudash", "admin", List.of());
        assertFalse(r.referencesPii);
    }

    @Test
    public void dashboard_reference_tile_recurses_and_flags_pii() {
        // Same shape as above but the saved query DOES touch a PII column.
        ds.put("/dash.saikudash", dashboardJson(List.of(referenceTileJson("/dirty-tile.saiku"))));
        ds.put(
                "/dirty-tile.saiku",
                thinQueryWithAxes(
                        "conn-1",
                        "Cat",
                        "Sch",
                        "Customer",
                        List.of(new LevelRef("Customer", "[Customer].[Customers]", "Email")),
                        List.of("Headcount")));
        AiSchema schema = cleanSchema();
        AiSchema.Dimension cust = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy custHier = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        AiSchema.Level email = new AiSchema.Level("Email", "[Customer].[Customers].[Email]");
        email.pii = true;
        custHier.levels.put(AiSchema.key("Email"), email);
        cust.hierarchies.put(AiSchema.key("Customers"), custHier);
        schema.dimensions.put(AiSchema.key("Customer"), cust);
        addCleanMeasure(schema, "Headcount");
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/dash.saikudash", "admin", List.of());
        assertTrue(r.referencesPii);
    }

    @Test
    public void dashboard_kpi_tile_flags_only_pii_measure() {
        // KPI tile carries a `kpi.measure` unique name. v1 would have
        // checked the whole cube; per-column must check ONLY the KPI's
        // measure.
        ds.put(
                "/kpi.saikudash",
                dashboardJson(List.of(kpiTileJson("conn-1", "Cat", "Sch", "Customer", "[Measures].[Headcount]"))));
        AiSchema schema = cleanSchema();
        // Clean measure on dirty cube — must NOT flag.
        addCleanMeasure(schema, "Headcount");
        AiSchema.Measure ssn = new AiSchema.Measure("SSN Count", "[Measures].[SSN Count]");
        ssn.pii = true;
        schema.measures.put(AiSchema.key("SSN Count"), ssn);
        cubes.putSchema("conn-1/Cat/Sch/Customer", schema);

        EmbedPiiInspector.Result r = inspector.inspect("dashboard", "/kpi.saikudash", "admin", List.of());
        assertFalse("KPI on clean measure, dirty cube — must not flag", r.referencesPii);
    }

    @Test
    public void lastBracketSegment_extracts_innermost_unique_name() {
        // Unit coverage for the helper that maps "[Customer].[Customers]"
        // → "Customers" so ThinHierarchy.name lookups hit AiSchema's
        // short-name keys.
        assertEquals("Customers", EmbedPiiInspector.lastBracketSegment("[Customer].[Customers]"));
        assertEquals("Customers", EmbedPiiInspector.lastBracketSegment("[Customers]"));
        assertEquals("Plain", EmbedPiiInspector.lastBracketSegment("Plain"));
        assertEquals(null, EmbedPiiInspector.lastBracketSegment(null));
    }

    /* -------------------------- defaults -------------------------- */

    @Test
    public void unknown_kind_fails_closed() {
        ds.put("/x.weird", "{}");
        EmbedPiiInspector.Result r = inspector.inspect("schema", "/x.weird", "admin", List.of());
        assertTrue(r.referencesPii);
        assertTrue(r.fellOpenForUnresolvable);
    }

    @Test
    public void null_or_blank_path_is_permissive() {
        // The caller's upstream validation rejects blank paths before we
        // see them — don't double-gate something that can't even be
        // identified.
        assertFalse(inspector.inspect("query", null, "admin", List.of()).referencesPii);
        assertFalse(inspector.inspect("query", "", "admin", List.of()).referencesPii);
    }

    @Test
    public void null_infrastructure_is_permissive_so_callers_can_run_without_it() {
        // No datasource / cube metadata services wired (stub / test
        // context). Inspector is a no-op; the surrounding GRANT check
        // remains the load-bearing security check.
        EmbedPiiInspector noop = new EmbedPiiInspector(null, null);
        EmbedPiiInspector.Result r = noop.inspect("query", "/anything.saiku", "admin", List.of());
        assertFalse(r.referencesPii);
    }

    /* --------------------------- helpers --------------------------- */

    private static AiSchema cleanSchema() {
        return new AiSchema("c/cat/sch/Cube", "Cube", "[Cube]");
    }

    /**
     * One cube schema shared by projectedPiiLevel_isDetected /
     * onlyNonPiiLevelsProjected_notFlagged: a pii=true Email level under
     * [Customer].[Customers], plus clean Time.Quarter, Product.Family and a
     * clean Store Sales measure. The SAME schema drives both the positive and
     * the negative so the pair proves the check is genuinely per-column and
     * that both sides derive matching keys.
     */
    private static AiSchema cubeWithPiiEmailLevel() {
        AiSchema schema = cleanSchema();
        AiSchema.Dimension cust = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy custHier = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        AiSchema.Level email = new AiSchema.Level("Email", "[Customer].[Customers].[Email]");
        email.pii = true;
        custHier.levels.put(AiSchema.key("Email"), email);
        cust.hierarchies.put(AiSchema.key("Customers"), custHier);
        schema.dimensions.put(AiSchema.key("Customer"), cust);
        addClean(schema, "Time", "Time", "Quarter");
        addClean(schema, "Product", "Product", "Family");
        addCleanMeasure(schema, "Store Sales");
        return schema;
    }

    private static String thinQueryJson(String conn, String cat, String sch, String cube) {
        return "{\"name\":\"q\",\"type\":\"QUERYMODEL\",\"cube\":{\"connection\":\"" + conn + "\",\"catalog\":\"" + cat
                + "\",\"schema\":\"" + sch + "\",\"name\":\"" + cube + "\"}}";
    }

    private static String dashboardJson(List<String> tileJsons) {
        StringBuilder sb =
                new StringBuilder("{\"id\":\"d\",\"name\":\"\",\"version\":1,\"layout\":{\"cols\":12,\"tiles\":[");
        for (int i = 0; i < tileJsons.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(tileJsons.get(i));
        }
        sb.append("]}}");
        return sb.toString();
    }

    private static String tileJson(String conn, String cat, String sch, String cube) {
        return "{\"id\":\"t\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"type\":\"chart\",\"cube\":{\"connectionName\":\"" + conn
                + "\",\"catalog\":\"" + cat + "\",\"schema\":\"" + sch + "\",\"cubeName\":\"" + cube + "\"}}";
    }

    /** A reference-style tile pointing at a saved-query path on disk. */
    private static String referenceTileJson(String savedQueryPath) {
        return "{\"id\":\"t\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"type\":\"chart\","
                + "\"query\":{\"kind\":\"reference\",\"path\":\"" + savedQueryPath + "\"}}";
    }

    /** A KPI tile bound to a specific measure unique name. */
    private static String kpiTileJson(String conn, String cat, String sch, String cube, String measureUniqueName) {
        return "{\"id\":\"t\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"type\":\"chart\","
                + "\"cube\":{\"connectionName\":\"" + conn + "\",\"catalog\":\"" + cat
                + "\",\"schema\":\"" + sch + "\",\"cubeName\":\"" + cube + "\"},"
                + "\"kpi\":{\"measure\":\"" + measureUniqueName + "\"}}";
    }

    /**
     * Build a ThinQuery JSON with axis selections — the per-column
     * inspector needs queryModel.axes populated to extract column refs.
     * Hierarchy names are stored as Mondrian unique names (the same shape
     * the engine emits) so the inspector exercises lastBracketSegment.
     */
    private static String thinQueryWithAxes(
            String conn, String cat, String sch, String cube, List<LevelRef> levels, List<String> measureNames) {
        return thinQueryWithAxes("ROWS", conn, cat, sch, cube, levels, measureNames);
    }

    /**
     * As {@link #thinQueryWithAxes(String, String, String, String, List, List)}
     * but places the projected hierarchies on the named axis (e.g. "ROWS" or
     * "COLUMNS"). {@code extractRefsFromThinQuery} walks every axis, so the
     * per-column PII check must hold regardless of which axis a PII level is
     * projected on.
     */
    private static String thinQueryWithAxes(
            String axis,
            String conn,
            String cat,
            String sch,
            String cube,
            List<LevelRef> levels,
            List<String> measureNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"q\",\"type\":\"QUERYMODEL\",\"cube\":{\"connection\":\"")
                .append(conn)
                .append("\",\"catalog\":\"")
                .append(cat)
                .append("\",\"schema\":\"")
                .append(sch)
                .append("\",\"name\":\"")
                .append(cube)
                .append("\"},\"queryModel\":{");
        sb.append("\"axes\":{\"")
                .append(axis)
                .append("\":{\"location\":\"")
                .append(axis)
                .append("\",\"hierarchies\":[");
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) sb.append(',');
            LevelRef ref = levels.get(i);
            sb.append("{\"name\":\"")
                    .append(ref.hierUniqueName)
                    .append("\",\"dimension\":\"")
                    .append(ref.dim)
                    .append("\",\"levels\":{\"")
                    .append(ref.level)
                    .append("\":{\"name\":\"")
                    .append(ref.level)
                    .append("\"}}}");
        }
        sb.append("]}},");
        sb.append("\"details\":{\"axis\":\"COLUMNS\",\"location\":\"BOTTOM\",\"measures\":[");
        for (int i = 0; i < measureNames.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(measureNames.get(i)).append("\"}");
        }
        sb.append("]}}}");
        return sb.toString();
    }

    /** Triple naming a level under a dim/hier. Hierarchy is the unique
     *  name (matching ThinHierarchy.name on disk). */
    private static class LevelRef {
        final String dim;
        final String hierUniqueName;
        final String level;

        LevelRef(String dim, String hierUniqueName, String level) {
            this.dim = dim;
            this.hierUniqueName = hierUniqueName;
            this.level = level;
        }
    }

    private static void addClean(AiSchema schema, String dim, String hier, String level) {
        AiSchema.Dimension d = schema.dimensions.get(AiSchema.key(dim));
        if (d == null) {
            d = new AiSchema.Dimension(dim, "[" + dim + "]");
            schema.dimensions.put(AiSchema.key(dim), d);
        }
        AiSchema.Hierarchy h = d.hierarchies.get(AiSchema.key(hier));
        if (h == null) {
            h = new AiSchema.Hierarchy(hier, "[" + dim + "].[" + hier + "]");
            d.hierarchies.put(AiSchema.key(hier), h);
        }
        h.levels.put(AiSchema.key(level), new AiSchema.Level(level, "[" + dim + "].[" + hier + "].[" + level + "]"));
    }

    private static void addCleanMeasure(AiSchema schema, String name) {
        schema.measures.put(AiSchema.key(name), new AiSchema.Measure(name, "[Measures].[" + name + "]"));
    }

    /* --------------------------- stubs --------------------------- */

    private static class StubDatasourceService extends DatasourceService {
        private final Map<String, String> files = new HashMap<>();
        private final java.util.Set<String> throwPaths = new java.util.HashSet<>();

        void put(String path, String content) {
            files.put(path, content);
        }

        void throwOn(String path) {
            throwPaths.add(path);
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if (throwPaths.contains(path)) throw new RuntimeException("simulated unreadable");
            return files.get(path);
        }
    }

    private static class StubCubeMetadataService implements AiCubeMetadataService {
        private final Map<String, AiSchema> schemas = new HashMap<>();
        private final java.util.Set<String> throwKeys = new java.util.HashSet<>();
        int lookups = 0;

        void putSchema(String cubeKey, AiSchema schema) {
            schemas.put(cubeKey, schema);
        }

        void throwOn(String cubeKey) {
            throwKeys.add(cubeKey);
        }

        @Override
        public AiSchema getSchema(AiCubeRef ref) {
            lookups++;
            String key =
                    ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
            if (throwKeys.contains(key)) throw new RuntimeException("simulated cube failure");
            return schemas.get(key);
        }
    }
}
