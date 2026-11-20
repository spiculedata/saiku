/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#2420 — behavioural acceptance for the raw DRILLTHROUGH row cap.
 *
 * <p>Run with the server ceiling {@code saiku.olap.max.rows=5} (set live —
 * {@code QueryGuardrails} reads the system property on every call — and restored after each
 * request), every raw drillthrough body must come back with at most 5 rows, whatever comment
 * prefixes the statement; and every statement the cap cannot prove bounded must be REFUSED
 * with a typed 400 (fixed message, no Mondrian parse noise) instead of executing unbounded or
 * surfacing as a 500/parse error.
 *
 * <p>Covers both raw entry points: {@code POST /api/query/execute} and
 * {@code POST /api/notebooks/run} (which delegates to the same {@code Query2Resource.execute}
 * path).
 */
public class DrillthroughRowCapIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXECUTE = "/rest/saiku/api/query/execute";
    private static final String NOTEBOOK_RUN = "/rest/saiku/api/notebooks/run";
    private static final String MAX_ROWS_KEY = "saiku.olap.max.rows";
    private static final String REFUSAL = "row cap";

    private static final String SELECT =
            "SELECT FROM [Sales] WHERE ([Measures].[Store Sales], [Product].[Products].[Drink])";

    @BeforeClass
    public static void boot() throws Exception {
        SaikuItHarness.shared();
    }

    private String executeBody(String name, String mdx) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name);
        body.put("type", "MDX");
        ObjectNode cube = body.putObject("cube");
        cube.put("connection", "unknown_foodmart");
        cube.put("catalog", "FoodMart");
        cube.put("schema", "FoodMart");
        cube.put("name", "Sales");
        cube.put("uniqueName", "[Sales]");
        body.put("mdx", mdx);
        return MAPPER.writeValueAsString(body);
    }

    private String notebookBody(String mdx) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("mdx", mdx);
        ObjectNode cube = body.putObject("cube");
        cube.put("connectionName", "unknown_foodmart");
        cube.put("catalog", "FoodMart");
        cube.put("schema", "FoodMart");
        cube.put("cubeName", "Sales");
        return MAPPER.writeValueAsString(body);
    }

    /** Rows in a drillthrough response (the header row is element 0). */
    private static int height(JsonNode r) {
        JsonNode cellset = r.path("cellset");
        assertTrue("expected a cellset array in: " + r, cellset.isArray() && cellset.size() >= 1);
        return cellset.size() - 1;
    }

    /** Run {@code body} through the endpoint with the ceiling at 5, restoring it after. */
    private Resp runCapped(String path, String body) throws Exception {
        String old = System.getProperty(MAX_ROWS_KEY);
        System.setProperty(MAX_ROWS_KEY, "5");
        try {
            HttpResponse<String> h = SaikuItHarness.shared().postAuthJson(path, body);
            return new Resp(h.statusCode(), SaikuItHarness.shared().parse(h), h.body());
        } finally {
            if (old == null) {
                System.clearProperty(MAX_ROWS_KEY);
            } else {
                System.setProperty(MAX_ROWS_KEY, old);
            }
        }
    }

    private record Resp(int status, JsonNode json, String raw) {}

    @Test
    public void commentedRawDrillthrough_isRowCappedOnExecuteAndNotebookRun() throws Exception {
        // Acceptance: with -Dsaiku.olap.max.rows=5, plain, /*a*/, --x\n and //x\n prefixed
        // raw drillthroughs each return at most 5 rows on BOTH raw entry points.
        String[] bodies = {
            "DRILLTHROUGH " + SELECT,
            "/*a*/DRILLTHROUGH " + SELECT,
            "--x\nDRILLTHROUGH " + SELECT,
            "//x\nDRILLTHROUGH " + SELECT,
        };
        int n = 0;
        for (String mdx : bodies) {
            Resp r = runCapped(EXECUTE, executeBody("drill-cap-" + n, mdx));
            assertEquals(
                    "drillthrough must execute capped on /query/execute: " + mdx + " → "
                            + r.raw().substring(0, Math.min(300, r.raw().length())),
                    200,
                    r.status());
            assertTrue("height must be <= 5 (got " + height(r.json()) + ") for: " + mdx, height(r.json()) <= 5);
            assertTrue("a row-capped drillthrough must return rows for: " + mdx, height(r.json()) >= 1);

            Resp nb = runCapped(NOTEBOOK_RUN, notebookBody(mdx));
            assertEquals(
                    "drillthrough must execute capped on /notebooks/run: " + mdx + " → " + nb.raw(), 200, nb.status());
            assertTrue("notebook height must be <= 5 for: " + mdx, height(nb.json()) <= 5);
            n++;
        }
    }

    @Test
    public void zeroMaxrows_returnsTypedRejection_not500() throws Exception {
        // MAXROWS 0 means UNLIMITED to Mondrian; the cap must refuse the statement, and the
        // refusal must be the typed, fixed-message 400 — not a leaked Mondrian parse error.
        Resp r = runCapped(EXECUTE, executeBody("drill-cap-zero", "DRILLTHROUGH MAXROWS 0 " + SELECT));
        assertEquals("MAXROWS 0 must be refused with a typed 400, got: " + r.raw(), 400, r.status());
        assertTrue(
                "refusal must carry the fixed typed message, got: " + r.raw(),
                r.json().path("error").asText("").contains(REFUSAL));
    }

    @Test
    public void commentedMaxrowsClauseWithBound_isCapped_not500() throws Exception {
        // A terminated comment inside the MAXROWS clause is unambiguous: the number after it is
        // the bound. The old behaviour prepended a second MAXROWS and guaranteed a Mondrian
        // parse error; now the statement is capped (and parser-verified) instead.
        Resp r = runCapped(EXECUTE, executeBody("drill-cap-cmt", "DRILLTHROUGH MAXROWS /*c*/ 999999999 " + SELECT));
        assertEquals(
                "commented MAXROWS clause must be capped, not a parse-error 500, got: " + r.raw(), 200, r.status());
        assertTrue("height must be <= 5, got " + height(r.json()), height(r.json()) <= 5);
    }

    @Test
    public void unterminatedBlockComment_neverExecutesAsADrillthrough() throws Exception {
        // The saiku#2420 hole: an unterminated block comment used to pass the statement through
        // the seam unchanged (fail-open). At the REST surface Mondrian's own lexer refuses the
        // statement (it never reaches the clamp), so the contract here is simply that NO rows
        // are ever returned for it — the seam-level fail-closed assertion is the unit test.
        Resp r = runCapped(EXECUTE, executeBody("drill-cap-open", "/* never closed DRILLTHROUGH " + SELECT));
        assertTrue("an unterminated-comment drillthrough must never execute: " + r.raw(), r.status() != 200);
    }

    @Test
    public void inflatedMaxrows_isClampedToTheCeiling() throws Exception {
        Resp r = runCapped(EXECUTE, executeBody("drill-cap-inflate", "DRILLTHROUGH MAXROWS 999999999 " + SELECT));
        assertEquals(200, r.status());
        assertTrue("height must be <= 5, got " + height(r.json()), height(r.json()) <= 5);
    }
}
