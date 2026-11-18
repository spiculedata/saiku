/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1920: the catch-all branches of the Ossie AI endpoints must not echo
 * {@code Throwable.getMessage()} (Avatica embeds the full SQL, including the row-level-security
 * predicate injected for the caller's role, plus JDBC URLs) to the caller. They return a stable
 * {@code SERVER_ERROR} code and a {@code ref} that is also in the server log.
 */
public class AiOssieResourceErrorEnvelopeTest {

    private static final String SECRET =
            "Error while executing SQL \"SELECT x FROM geo WHERE REGION IN ('APAC')\": boom jdbc:h2:/data/dw";

    private AiOssieResource resource;

    @Before
    public void setUp() {
        resource = new AiOssieResource();
        resource.setOssieDiscoverService(new AiOssieResourceAsyncRoleTest.StubDiscover());
        resource.setOssieQueryService(new OssieQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) throws Exception {
                throw new SQLException(SECRET);
            }

            @Override
            public String previewSql(OssieQueryModel model) {
                throw new IllegalStateException(SECRET);
            }
        });
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void executeFailureDoesNotEchoCauseMessage() {
        assertSanitised(resource.executeAi(request(), "records"));
    }

    @Test
    public void rowDetailFailureDoesNotEchoCauseMessage() {
        assertSanitised(resource.rowDetail(request(), null));
    }

    @Test
    public void previewFailureDoesNotEchoCauseMessage() {
        assertSanitised(resource.previewAi(request()));
    }

    @Test
    public void validationErrorsStayInformative() {
        OssieAiQueryRequest req = request();
        req.getRows().get(0).setField("no_such_field");
        Response resp = resource.executeAi(req, "records");
        assertEquals(400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertEquals("VALIDATION_ERROR", entity.get("error"));
        assertTrue(String.valueOf(entity.get("message")).contains("no_such_field"));
    }

    private static void assertSanitised(Response resp) {
        assertEquals(500, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertEquals("SERVER_ERROR", entity.get("error"));
        String text = String.valueOf(entity);
        assertFalse("body leaked the exception text: " + text, text.contains("REGION IN"));
        assertFalse("body leaked the exception text: " + text, text.contains("jdbc:"));
        assertFalse("body leaked the exception text: " + text, text.contains("boom"));
        assertTrue(
                "body must carry a log ref: " + text,
                String.valueOf(entity.get("ref")).matches("[0-9a-f]{8}"));
    }

    private static OssieAiQueryRequest request() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection("Geo");
        OssieAiQueryRequest.FieldRef f = new OssieAiQueryRequest.FieldRef();
        f.setDataset("geography");
        f.setField("region");
        req.getRows().add(f);
        OssieAiQueryRequest.MetricRef v = new OssieAiQueryRequest.MetricRef();
        v.setMetric("row_count");
        req.getValues().add(v);
        return req;
    }
}
