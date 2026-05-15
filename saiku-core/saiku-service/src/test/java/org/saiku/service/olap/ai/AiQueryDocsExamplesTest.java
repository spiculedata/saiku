/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;

/**
 * Self-executing doc examples (Phase 4 / saiku#10). Each test pins one
 * example body from {@code docs/AI-QUERY-API.md} — when the converter
 * drifts away from a documented {@code generatedMdx} string, this test
 * fails and forces the doc to update in lockstep.
 *
 * <p>Reuses {@link FoodmartTestFixture} (minimal in-process FoodMart
 * mirror) so the doc keeps reading natural ("Store Sales", "Product
 * Family") rather than being rewritten against the Quirks fixture.
 *
 * <p>Tests pin <i>byte-for-byte MDX</i>, not just semantic equivalence —
 * the docs ship the exact MDX string, so any reshape (extra space,
 * dropped Hierarchize, different sort-key order) must surface here.
 *
 * <p>If a converter change is intentional, update the {@code EXPECTED_*}
 * constants <i>and</i> the corresponding {@code ```json} block in the
 * markdown before committing — one change moves both in lockstep.
 */
public class AiQueryDocsExamplesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AiSchemaConverter converter = new AiSchemaConverter();
    private final AiSchema schema = FoodmartTestFixture.directSchema();

    /* ---------- AI-QUERY-API.md § "Step 3 — execute a query" (line ~219) ---------- */

    private static final String STEP3_BODY = "{\n"
            + "  \"measures\": [\n"
            + "    { \"name\": \"Store Sales\" },\n"
            + "    { \"name\": \"Unit Sales\" }\n"
            + "  ],\n"
            + "  \"rows\": [\n"
            + "    { \"dimension\": \"Product\", \"hierarchy\": \"Products\", \"level\": \"Product Family\" }\n"
            + "  ],\n"
            + "  \"order\": [{ \"by\": \"Store Sales\", \"direction\": \"desc\" }],\n"
            + "  \"limit\": 3\n"
            + "}";

    private static final String STEP3_EXPECTED_MDX = "SELECT NON EMPTY {[Measures].[Store Sales], [Measures].[Unit Sales]} ON COLUMNS,\n"
            + "NON EMPTY TopCount([Product].[Products].[Product Family].Members, 3, [Measures].[Store Sales]) ON ROWS\n"
            + "FROM [Sales]";

    @Test
    public void step3_topNByMeasure_convertsToDocumentedMdx() throws Exception {
        AiQueryRequest req = MAPPER.readValue(STEP3_BODY, AiQueryRequest.class);
        req.setCube(FoodmartTestFixture.cubeRef());

        ThinQuery tq = converter.convert(req, schema);
        assertNotNull(tq);
        assertEquals(
                "Step 3 example MDX must match the string published at AI-QUERY-API.md line ~256.\n"
                        + "If this changed intentionally, update both the doc and the EXPECTED constant.",
                STEP3_EXPECTED_MDX,
                tq.getMdx());
    }

    /* ---------- AI-QUERY-API.md § "Relative-time filters" (line ~602) ---------- */
    /* The doc body has no paired generatedMdx claim — we pin the converter's
       current emit so a future change forces an explicit doc update. */

    private static final String RELATIVE_TIME_BODY = "{\n"
            + "  \"measures\": [{ \"name\": \"Store Sales\" }],\n"
            + "  \"rows\": [{ \"dimension\": \"Product\", \"hierarchy\": \"Products\", \"level\": \"Product Family\" }],\n"
            + "  \"filters\": [{\n"
            + "    \"dimension\": \"Time\",\n"
            + "    \"hierarchy\": \"Time By\",\n"
            + "    \"level\": \"Day\",\n"
            + "    \"op\": \"relative\",\n"
            + "    \"value\": \"last_n_days\",\n"
            + "    \"n\": 30\n"
            + "  }]\n"
            + "}";

    @Test
    public void relativeTime_last30Days_emitsTailExpressionInSlicer() throws Exception {
        AiQueryRequest req = MAPPER.readValue(RELATIVE_TIME_BODY, AiQueryRequest.class);
        req.setCube(FoodmartTestFixture.cubeRef());

        ThinQuery tq = converter.convert(req, schema);
        assertNotNull(tq);
        String mdx = tq.getMdx();
        // The doc promises Tail(level.Members, n) at the Day level — assert
        // that shape rather than the full MDX, since the doc doesn't pin it
        // and changes to surrounding axes shouldn't fail this test.
        assertTrue(
                "Expected Tail(...) at Day level for last_n_days — got:\n" + mdx,
                mdx.contains("Tail([Time].[Time By].[Day].Members, 30)"));
        assertTrue(
                "Relative-time filter belongs in the WHERE slicer per doc § Relative-time filters",
                mdx.contains("WHERE"));
    }
}
