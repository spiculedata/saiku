/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;

/**
 * saiku#1282 regression: the AI Query API must NOT echo raw exception detail to the client. On an
 * unexpected execution failure it returns a generic message (e.g. "execute failed") and logs the
 * detail server-side only — the same info-disclosure class as the #1261 QueryResource leak. The
 * structured {@code AiValidationException} errors (field + candidate list) are intentional and stay.
 *
 * <p>Drives the real {@link AiQueryResource#executeAi} with a {@link ThinQueryService} whose
 * execute() throws a credential-bearing message, and asserts the response carries only the generic
 * text — never the secret. Mirrors {@code AiQueryResourceTest}'s stub-injection style.
 */
public class AiQueryErrorEchoTest {

    /** A realistic deepest-cause: a JDBC URL with an embedded password. */
    private static final String SECRET =
            "jdbc:postgresql://10.0.0.5:5432/warehouse?user=svc&password=hunter2 [SQLState=28000]";

    private AiQueryResource resource;

    @Before
    public void setUp() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeBy = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeBy.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time By].[Year]"));
        time.hierarchies.put(AiSchema.key("Time By"), timeBy);
        schema.dimensions.put(AiSchema.key("Time"), time);

        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
        // A service whose execution blows up with a sensitive message.
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                throw new RuntimeException(SECRET);
            }
        });
    }

    private AiQueryRequest baseRequest() {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        req.setMeasures(Collections.singletonList(new AiMeasureSelection("Store Sales")));
        req.setRows(Collections.singletonList(new AiAxisSelection("Time", "Time By", "Year")));
        return req;
    }

    @Test
    public void executeFailureReturnsGenericError_doesNotEchoExceptionDetail() {
        Response resp = resource.executeAi(baseRequest(), "records");

        assertEquals(500, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertNotNull(body);
        assertEquals(AiQueryResponse.Status.EXECUTION_ERROR, body.getStatus());
        assertEquals("execute failed", body.getError());
        assertFalse(
                "raw exception detail must never reach the client: " + body.getError(),
                body.getError() != null && body.getError().contains("password=hunter2"));
    }
}
