/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1803 — the request bodies an Ossie-backed TILE emits, run against the real stack.
 *
 * <p>The UI-side unit tests prove the bodies are BUILT correctly; this proves the server
 * answers them, which is the half that can't be mocked. Each test posts the exact shape
 * {@code ossieEffectiveQueryFor()} / {@code KpiTile}'s ossie branch produce — not a
 * hand-tuned query — so a drift between what the tile sends and what the endpoint accepts
 * fails here rather than in someone's browser.
 *
 * <p>It also pins the envelope, which is NOT the MDX one and was assumed to be: rows arrive
 * as {@code records} (not {@code data}), the column descriptors are top-level (not under
 * {@code metadata}), and there is no {@code status} on success. What matches is the CELL —
 * {@code {value, formatted}} for a measure, a plain string for a row header — which is what
 * lets the UI-side adapter (ossieResponse.ts) rename the wrapper and leave every renderer
 * untouched. These assertions are what that adapter is written against, so a server-side
 * change to the envelope fails here rather than silently blanking every model-backed tile.
 *
 * <p>The harness's temp home stages the Flights + TPC-DS Ossie demos on first boot, so the
 * model is present without any fixture of our own.
 */
public class OssieTileQueryIT {

    private static SaikuItHarness harness;
    private static final String QUERY = "/rest/saiku/api/ai/ossie/query?format=records";
    private static final String MDX_QUERY = "/rest/saiku/api/ai/query?format=records";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> resp = harness.postAuthJson(path, body);
        assertEquals("expected 200, got " + resp.statusCode() + " body=" + resp.body(), 200, resp.statusCode());
        return harness.parse(resp);
    }

    @Test
    public void ossieModelsAreDiscoverable() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/rest/saiku/api/ai/ossie/models");
        assertEquals(200, resp.statusCode());
        assertTrue(
                "the Flights demo model must be staged in a fresh home — got: " + resp.body(),
                resp.body().contains("Flights"));
    }

    /** A chart / table tile: metrics on values, a dimension field on rows. */
    @Test
    public void chartTileBodyReturnsRows() throws Exception {
        String body =
                """
                {
                  "connection": "unknown_Flights",
                  "model": "Flights",
                  "values": [{"metric": "flight_count"}],
                  "rows": [{"dataset": "carrier", "field": "carrier_name"}]
                }
                """;
        JsonNode r = post(QUERY, body);
        assertTrue(
                "expected carrier rows, got: " + r.path("records"),
                r.path("records").size() > 0);
    }

    /**
     * The response must be shaped like the MDX one, because the tiles don't know which
     * endpoint answered them. Row-header columns are plain strings, measure columns are
     * objects carrying {@code value} + {@code formatted} — that is exactly what
     * projectFromAiQueryResponse() keys off to tell a category from a measure.
     */
    @Test
    public void responseEnvelopeMatchesTheMdxOne() throws Exception {
        String ossie =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"carrier","field":"carrier_name"}]}
                """;
        String mdx =
                """
                {"cube":"unknown_foodmart/FoodMart/FoodMart/Store",
                 "measures":[{"name":"Store Sqft"}],
                 "rows":[{"dimension":"Store","hierarchy":"Stores","level":"Store Country"}]}
                """;
        JsonNode o = post(QUERY, ossie);
        JsonNode m = post(MDX_QUERY, mdx);

        // The WRAPPERS differ, and the adapter exists because of exactly this.
        assertTrue("ossie rows live on `records`", o.has("records"));
        assertFalse("ossie has no `data`", o.has("data"));
        assertFalse("ossie has no `status` on success", o.has("status"));
        assertTrue("ossie column descriptors are top-level", o.path("columns").isArray());

        assertTrue("mdx rows live on `data`", m.has("data"));
        assertEquals("SUCCESS", m.path("status").asText());
        assertTrue(
                "mdx columns live under metadata",
                m.path("metadata").path("columns").isArray());

        // The CELLS match, which is what makes the adapter a rename rather than
        // a translation, and what keeps every renderer downstream unchanged.
        for (JsonNode firstRow :
                new JsonNode[] {o.path("records").get(0), m.path("data").get(0)}) {
            assertNotNull("expected at least one row", firstRow);
            boolean sawHeader = false;
            boolean sawTypedCell = false;
            for (JsonNode cell : firstRow) {
                if (cell.isObject() && cell.has("value") && cell.has("formatted")) sawTypedCell = true;
                else if (cell.isTextual()) sawHeader = true;
            }
            assertTrue("a row must carry a plain-string row header", sawHeader);
            assertTrue("a row must carry a typed measure cell", sawTypedCell);
        }
    }

    /** A KPI tile: one metric, no rows — the single-cell shape KpiTile's ossie branch sends. */
    @Test
    public void kpiTileBodyReturnsASingleCell() throws Exception {
        String body =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],"rows":[],"filters":[]}
                """;
        JsonNode r = post(QUERY, body);
        assertEquals(
                "a KPI query must come back as exactly one row",
                1,
                r.path("records").size());
    }

    /**
     * The semantic-filter payload: the caption selected on a cube-shaped control, applied to
     * the model through its binding. This is the body ossieFiltersFor() produces for
     * {@code State = CA} bound to {@code airport.airport_state}.
     */
    @Test
    public void semanticFilterPredicateNarrowsTheModel() throws Exception {
        String unfiltered =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"airport","field":"airport_state"}]}
                """;
        String filtered =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"airport","field":"airport_state"}],
                 "filters":[{"dataset":"airport","field":"airport_state","op":"EQ","value":"CA"}]}
                """;
        JsonNode all = post(QUERY, unfiltered);
        JsonNode wa = post(QUERY, filtered);

        assertTrue(
                "the unfiltered query should span several states",
                all.path("records").size() > 1);
        assertEquals(
                "EQ on a state must leave exactly that state",
                1,
                wa.path("records").size());
    }

    /** The multi-select form of the same control. */
    @Test
    public void inPredicateAcceptsSeveralCaptions() throws Exception {
        String body =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"airport","field":"airport_state"}],
                 "filters":[{"dataset":"airport","field":"airport_state","op":"IN","values":["CA","GA"]}]}
                """;
        JsonNode r = post(QUERY, body);
        assertEquals(2, r.path("records").size());
    }

    /**
     * A caption one source knows and the other doesn't is the documented consequence of
     * carrying the selection as a caption. It must come back as an empty SUCCESS — the tile
     * then renders "no data … " naming the source (saiku#1804) — and NOT as an error, which
     * would read to the user as a broken tile rather than an empty slice.
     */
    @Test
    public void aCaptionTheModelDoesNotKnowIsEmptyNotAnError() throws Exception {
        // FoodMart has Washington stores (Seattle, Tacoma, Spokane, Bremerton). The
        // flights fixture HAS a Seattle airport but never uses it as an origin, so
        // the model genuinely has no WA rows — the same user-visible outcome as a
        // caption it has never heard of, and a more realistic one.
        String body =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"airport","field":"airport_state"}],
                 "filters":[{"dataset":"airport","field":"airport_state","op":"EQ","value":"WA"}]}
                """;
        // post() asserts 200 — the point is that this is a RESULT, not an error.
        JsonNode r = post(QUERY, body);
        assertFalse("an empty slice must not be reported as an error", r.has("error"));
        assertEquals(
                "an unknown caption is an empty slice, not a failure",
                0,
                r.path("records").size());
    }

    /** Validation errors must arrive in the self-correcting envelope the tiles render. */
    @Test
    public void unknownFieldReturnsAStructuredValidationError() throws Exception {
        String body =
                """
                {"connection":"unknown_Flights","model":"Flights",
                 "values":[{"metric":"flight_count"}],
                 "rows":[{"dataset":"carrier","field":"nope"}]}
                """;
        HttpResponse<String> resp = harness.postAuthJson(QUERY, body);
        assertEquals(400, resp.statusCode());
        JsonNode r = harness.parse(resp);
        assertTrue("must name the offending field: " + resp.body(), resp.body().contains("nope"));
        assertTrue(
                "must offer candidates so the author can self-correct: " + resp.body(),
                r.has("available") || resp.body().contains("carrier_name"));
    }
}
