/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;

/**
 * saiku#1780 — a requested measure with no join path to a filtered dimension
 * is dropped from the result by Mondrian. It used to vanish silently: the
 * agent asking for N measures got fewer than N columns back with no
 * explanation. These tests pin the fix: the dropped measure is surfaced
 * explicitly as a self-describing "unavailable" cell (null value + a
 * machine-readable reason), normal queries keep their exact shape, and a
 * ragged/short row can't trip an AIOOBE.
 */
public class AiQueryResourceDroppedMeasureTest {

    private AiQueryResource resource;
    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        // Store Sales carries a Phase-3 display rename + a #818 synonym, so the
        // label an agent sends can differ from the result column caption. This
        // is the exact shape the SEC/QA block was about.
        AiSchema.Measure storeSales = new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]");
        storeSales.displayName = "Total Sales";
        storeSales.synonyms.add("revenue");
        schema.measures.put(AiSchema.key("Store Sales"), storeSales);
        // measureAliases: display name + synonym -> canonical map key (mirrors
        // how OlapAiCubeMetadataService registers Phase-3 aliases / synonyms).
        schema.measureAliases.put(AiSchema.key("Total Sales"), AiSchema.key("Store Sales"));
        schema.measureAliases.put(AiSchema.key("revenue"), AiSchema.key("Store Sales"));
        // A measure that (in this fixture) has no join path to the slicer —
        // the stub CellDataSet deliberately omits its column.
        schema.measures.put(
                AiSchema.key("Warehouse Cost"), new AiSchema.Measure("Warehouse Cost", "[Measures].[Warehouse Cost]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        // Product for the ROWS axis, kept distinct from the Time slicer so the
        // converter's "same hierarchy on axis and slicer" rule never fires.
        AiSchema.Dimension product = new AiSchema.Dimension("Product", "[Product]");
        AiSchema.Hierarchy products = new AiSchema.Hierarchy("Products", "[Product].[Products]");
        products.levels.put(
                AiSchema.key("Product Family"),
                new AiSchema.Level("Product Family", "[Product].[Products].[Product Family]"));
        product.hierarchies.put(AiSchema.key("Products"), products);
        schema.dimensions.put(AiSchema.key("Product"), product);

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
    }

    /** Request Store Sales + Warehouse Cost; the stub returns ONLY the Store
     *  Sales column (Warehouse Cost dropped for no join path). The response
     *  must still carry Warehouse Cost, explicitly, as an unavailable cell. */
    @Test
    public void droppedMeasureSurfacedAsUnavailableCellInRecords() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return storeSalesOnlyByYear();
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Arrays.asList(new AiMeasureSelection("Store Sales"), new AiMeasureSelection("Warehouse Cost")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Product", "Products", "Product Family")));
        // A YTD relative filter on Time resolves without needing sample
        // members, giving us a real slicer dimension for the reason string.
        AiFilterSelection ytd = new AiFilterSelection();
        ytd.setDimension("Time");
        ytd.setHierarchy("Time By");
        ytd.setLevel("Year");
        ytd.setOp("relative");
        ytd.setValue("ytd");
        req.setFilters(Collections.singletonList(ytd));

        Response resp = resource.executeAi(req, "records");
        if (resp.getStatus() != 200) {
            AiQueryResponse bad = (AiQueryResponse) resp.getEntity();
            throw new AssertionError(
                    "expected 200, got " + resp.getStatus() + " field=" + bad.getField() + " error=" + bad.getError());
        }
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();

        List<Map<String, Object>> data = body.getData();
        assertEquals("two rows retained", 2, data.size());

        for (Map<String, Object> row : data) {
            // Present measure is a normal typed cell.
            AiCell ss = (AiCell) row.get("Store Sales");
            assertNotNull("Store Sales cell present", ss);
            assertNull("present measure carries no unavailable reason", ss.getUnavailable());
            // Dropped measure is present but explicitly unavailable.
            assertTrue("dropped measure surfaced as a key, not silently omitted", row.containsKey("Warehouse Cost"));
            AiCell wc = (AiCell) row.get("Warehouse Cost");
            assertNotNull("Warehouse Cost cell present (explicit, not null-missing)", wc);
            assertNull("unavailable cell has null value", wc.getValue());
            assertNull("unavailable cell has null formatted", wc.getFormatted());
            assertNotNull("unavailable reason is machine-readable", wc.getUnavailable());
            assertTrue(
                    "reason names the filtered dimension: " + wc.getUnavailable(),
                    wc.getUnavailable().contains("Time"));
        }

        // metadata.measures reflects everything requested, incl. the dropped one.
        assertTrue(
                "measures metadata includes the dropped measure",
                body.getMetadata().getMeasures().contains("Warehouse Cost"));
    }

    /** A normal query where every requested measure IS present must keep its
     *  exact historical shape — no unavailable cells, nothing extra. */
    @Test
    public void normalQueryShapeUnchangedWhenNothingDropped() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return storeSalesOnlyByYear();
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        Response resp = resource.executeAi(req, "records");
        assertEquals(200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();

        List<Map<String, Object>> data = body.getData();
        assertEquals(2, data.size());
        Map<String, Object> row0 = data.get(0);
        assertEquals("1997", row0.get("Year"));
        AiCell cell = (AiCell) row0.get("Store Sales");
        assertEquals(Double.valueOf(100.0), cell.getValue());
        assertNull("no unavailable reason on a present measure", cell.getUnavailable());
        assertFalse("no phantom dropped-measure key", row0.containsKey("Warehouse Cost"));
        assertEquals("only the row-header + the one measure", 2, row0.size());
    }

    /** SEC/QA regression (end-to-end): request a PRESENT measure by its DISPLAY
     *  name while the result column returns the CANONICAL caption. The measure
     *  is present under its canonical label, so it must NOT be phantom-flagged
     *  unavailable under "Total Sales". */
    @Test
    public void presentMeasureRequestedByDisplayNameNotFlagged() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return storeSalesOnlyByYear(); // column caption = "Store Sales"
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Total Sales"))); // display name
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        Response resp = resource.executeAi(req, "records");
        assertEquals(200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();

        for (Map<String, Object> row : body.getData()) {
            assertFalse("must not phantom-flag the display name", row.containsKey("Total Sales"));
            AiCell ss = (AiCell) row.get("Store Sales");
            assertNotNull("present measure still rendered under its column caption", ss);
            assertNull("no unavailable reason on a present measure", ss.getUnavailable());
        }
    }

    /** SEC/QA regression (end-to-end): request a PRESENT measure by a SYNONYM
     *  (#818) resolving to a present canonical measure -> nothing flagged. */
    @Test
    public void presentMeasureRequestedBySynonymNotFlagged() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return storeSalesOnlyByYear(); // column caption = "Store Sales"
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("revenue"))); // synonym
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        Response resp = resource.executeAi(req, "records");
        assertEquals(200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();

        for (Map<String, Object> row : body.getData()) {
            assertFalse("must not phantom-flag the synonym", row.containsKey("revenue"));
            AiCell ss = (AiCell) row.get("Store Sales");
            assertNotNull(ss);
            assertNull(ss.getUnavailable());
        }
        // Nothing dropped -> only the canonical measure appears in metadata.
        assertFalse(
                "no phantom dropped measure in metadata",
                body.getMetadata().getMeasures().contains("revenue"));
    }

    /** Matrix format: the dropped measure lands as a trailing indexed column
     *  with the unavailable reason. */
    @Test
    public void droppedMeasureSurfacedInMatrix() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return storeSalesOnlyByYear();
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Arrays.asList(new AiMeasureSelection("Store Sales"), new AiMeasureSelection("Warehouse Cost")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        Response resp = resource.executeAi(req, "matrix");
        assertEquals(200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();

        List<Map<String, AiCell>> matrix = body.getMatrix();
        assertEquals(2, matrix.size());
        // col 0 = Store Sales (present), col 1 = Warehouse Cost (unavailable).
        AiCell present = matrix.get(0).get("0");
        assertNotNull(present);
        assertNull(present.getUnavailable());
        AiCell unavailable = matrix.get(0).get("1");
        assertNotNull("dropped measure occupies a trailing matrix column", unavailable);
        assertNull(unavailable.getValue());
        assertNotNull(unavailable.getUnavailable());
    }

    /* --------------------- detectDroppedMeasures unit --------------------- */

    @Test
    public void detectDroppedMeasuresFlagsAbsentAndPhrasesReason() {
        // Two measures, each its own label group. Store Sales present, Warehouse
        // Cost absent -> exactly one dropped, keyed by its surfacing label.
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Arrays.asList(Collections.singletonList("Store Sales"), Collections.singletonList("Warehouse Cost")),
                Collections.singletonList("Store Sales"),
                Collections.singletonList("Warehouse"));
        assertEquals("exactly one measure dropped", 1, dropped.size());
        AiCell wc = dropped.get("Warehouse Cost");
        assertNotNull(wc);
        assertEquals("no join path to filtered dimension(s): Warehouse", wc.getUnavailable());
    }

    @Test
    public void detectDroppedMeasuresIsCaseAndWhitespaceInsensitive() {
        // Requested "unit sales" (lower) matches the present "Unit Sales" column.
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Collections.singletonList(Collections.singletonList("  unit sales  ")),
                Collections.singletonList("Unit Sales"),
                Collections.emptyList());
        assertTrue("normalised match — nothing dropped", dropped.isEmpty());
    }

    @Test
    public void detectDroppedMeasuresEmptyWhenNothingRequested() {
        assertTrue(AiQueryResource.detectDroppedMeasures(
                        Collections.emptyList(), Collections.singletonList("Store Sales"), Collections.emptyList())
                .isEmpty());
    }

    @Test
    public void detectDroppedMeasuresGenericReasonWithoutFilters() {
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Collections.singletonList(Collections.singletonList("Warehouse Cost")),
                Collections.emptyList(),
                Collections.emptyList());
        assertEquals(
                "no join path to a filtered dimension",
                dropped.get("Warehouse Cost").getUnavailable());
    }

    /** Regression for the SEC/QA block: a PRESENT measure whose label group
     *  carries a DISPLAY-NAME rename (or synonym) that differs from the result
     *  column caption must NOT be flagged. The measure is present under its
     *  canonical label; the other labels are just aliases for the SAME measure,
     *  so grouping means ANY match = present. A flat label set would phantom-
     *  flag "Total Sales" / "revenue" here. */
    @Test
    public void detectDroppedMeasuresDoesNotFlagPresentMeasureWithDisplayRename() {
        // One measure, three labels: canonical "Store Sales" (the column),
        // display rename "Total Sales", synonym "revenue". Column returns the
        // canonical caption only.
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Collections.singletonList(Arrays.asList("Store Sales", "Total Sales", "revenue")),
                Collections.singletonList("Store Sales"),
                Collections.singletonList("Warehouse"));
        assertTrue("aliased present measure must not be phantom-flagged", dropped.isEmpty());
    }

    /** Mirror of the above: the column returns the DISPLAY caption and the
     *  caller sent the canonical (or a synonym). Still one measure, still
     *  present — nothing dropped. */
    @Test
    public void detectDroppedMeasuresDoesNotFlagWhenColumnUsesDisplayCaption() {
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Collections.singletonList(Arrays.asList("Store Sales", "Total Sales")),
                Collections.singletonList("Total Sales"),
                Collections.emptyList());
        assertTrue("present under the display caption — not dropped", dropped.isEmpty());
    }

    /** A present measure and a genuinely-dropped measure requested together:
     *  the present one (with aliases) yields ZERO cells, the dropped one yields
     *  exactly ONE. */
    @Test
    public void detectDroppedMeasuresMixedPresentAliasedAndDropped() {
        Map<String, AiCell> dropped = AiQueryResource.detectDroppedMeasures(
                Arrays.asList(
                        Arrays.asList("Store Sales", "Total Sales", "revenue"),
                        Collections.singletonList("Warehouse Cost")),
                Collections.singletonList("Store Sales"),
                Collections.singletonList("Customer"));
        assertEquals("only the genuine no-join-path measure is dropped", 1, dropped.size());
        assertTrue(dropped.containsKey("Warehouse Cost"));
        assertFalse("aliased present measure produced no cell", dropped.containsKey("Total Sales"));
        assertFalse("aliased present measure produced no cell", dropped.containsKey("revenue"));
    }

    /* ------------------------- ragged-row guard --------------------------- */

    /** A body row shorter than totalWidth (ragged CellDataSet) must not throw
     *  ArrayIndexOutOfBounds — the data loops now guard row[c]. */
    @Test
    public void raggedShortRowDoesNotThrow() {
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return raggedByYear();
            }
        });

        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));

        Response resp = resource.executeAi(req, "records");
        assertEquals("ragged row rendered without AIOOBE", 200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(2, body.getData().size());
    }

    /* ----------------------------- fixtures ------------------------------- */

    /** 2-row result with a single measure column "Store Sales" (rows = years).
     *  Mimics Mondrian having dropped every other requested measure. */
    private static CellDataSet storeSalesOnlyByYear() {
        CellDataSet cds = new CellDataSet(2, 1);
        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("Year");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

        AbstractBaseCell r1m = new MemberCell(false, false);
        r1m.setFormattedValue("1997");
        AbstractBaseCell r1d = new DataCell(true, false, null);
        r1d.setFormattedValue("100");
        r1d.setRawValue("100");

        AbstractBaseCell r2m = new MemberCell(false, false);
        r2m.setFormattedValue("1998");
        AbstractBaseCell r2d = new DataCell(true, false, null);
        r2d.setFormattedValue("200");
        r2d.setRawValue("200");

        cds.setCellSetBody(new AbstractBaseCell[][] {
            new AbstractBaseCell[] {r1m, r1d},
            new AbstractBaseCell[] {r2m, r2d},
        });
        return cds;
    }

    /** A ragged CellDataSet: headers declare 2 columns, but the second body
     *  row is short (only the row-header cell, no data cell). */
    private static CellDataSet raggedByYear() {
        CellDataSet cds = new CellDataSet(2, 1);
        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("Year");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

        AbstractBaseCell r1m = new MemberCell(false, false);
        r1m.setFormattedValue("1997");
        AbstractBaseCell r1d = new DataCell(true, false, null);
        r1d.setFormattedValue("100");
        r1d.setRawValue("100");

        AbstractBaseCell r2m = new MemberCell(false, false);
        r2m.setFormattedValue("1998");

        cds.setCellSetBody(new AbstractBaseCell[][] {
            new AbstractBaseCell[] {r1m, r1d},
            new AbstractBaseCell[] {r2m}, // short row — no data cell
        });
        return cds;
    }
}
