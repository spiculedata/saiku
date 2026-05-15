/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
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
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Unit test for {@link AiQueryResource}. Mirrors the stub pattern used by
 * Query2ResourceArrowTest: we inject fake services that return a hand-rolled
 * CellDataSet, then assert on the JSON response.
 */
public class AiQueryResourceTest {

    private AiQueryResource resource;
    private AiSchema schema;

    @Before
    public void setUp() {
        schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(AiSchema.key("Store Sales"),
                new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"),
                new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
        resource.setThinQueryService(new StubThinQueryService());
    }

    private AiQueryRequest baseRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        return req;
    }

    @Test
    public void happyPathReturns200WithMatrix() {
        Response resp = resource.executeAi(baseRequest());
        assertEquals(200, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.SUCCESS, body.getStatus());
        assertNotNull(body.getQueryId());
        assertNotNull(body.getMetadata());
        assertNotNull("generated MDX should be echoed", body.getMetadata().getGeneratedMdx());
        assertTrue("MDX should reference the schema cube",
                body.getMetadata().getGeneratedMdx().contains("[FoodMart].[Sales]"));

        List<Map<String, String>> matrix = body.getMatrix();
        assertEquals("2 rows in stubbed result", 2, matrix.size());
        assertEquals("100", matrix.get(0).get("0"));
        assertEquals("200", matrix.get(1).get("0"));

        assertEquals("row caption from MemberCell", "1997", body.getMetadata().getRows().get(0).getCaption());
        assertEquals("column caption from header", "Store Sales", body.getMetadata().getColumns().get(0).getCaption());
    }

    @Test
    public void unknownMeasureReturns400WithStructuredError() {
        AiQueryRequest req = baseRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Bogus")));

        Response resp = resource.executeAi(req);
        assertEquals(400, resp.getStatus());

        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("measures[].name", body.getField());
        assertTrue("error should list valid measures", body.getAvailable().contains("Store Sales"));
    }

    @Test
    public void missingCubeReturns400() {
        AiQueryRequest req = new AiQueryRequest();
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));

        Response resp = resource.executeAi(req);
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cube", body.getField());
    }

    @Test
    public void cubeMetadataServiceErrorPropagatesAs400() {
        resource.setCubeMetadataService(ref -> {
            throw new AiValidationException("cube", "Unknown cube " + ref, Collections.singletonList("Sales"));
        });

        Response resp = resource.executeAi(baseRequest());
        assertEquals(400, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals("cube", body.getField());
        assertTrue(body.getAvailable().contains("Sales"));
    }

    /* ------------------------ stub impls --------------------------------- */

    /** Returns a hand-rolled 2x1 CellDataSet (rows = [1997, 1998], measures = [Store Sales]). */
    private static class StubThinQueryService extends ThinQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return buildStubCellDataSet();
        }
    }

    static CellDataSet buildStubCellDataSet() {
        CellDataSet cds = new CellDataSet(2, 1);
        // Header rows: column header containing the measure caption.
        // Shape: [emptyRowHeader][measureCaption]
        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] { new AbstractBaseCell[] { hdrA, hdrB } });

        // Body: [yearMemberCell][dataCell]
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
                new AbstractBaseCell[] { r1m, r1d },
                new AbstractBaseCell[] { r2m, r2d },
        });
        return cds;
    }
}
