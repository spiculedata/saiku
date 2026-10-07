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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.CertifiedQueryParser;
import org.saiku.service.olap.ai.ask.CertifiedQueryRegistry;
import org.saiku.service.olap.ai.ask.NlAskMessage;
import org.saiku.service.olap.ai.ask.NlAskRequest;

/**
 * saiku#1430 — resource-level tests for the certified catalogue: {@code GET /ai/certified}, {@code
 * POST /ai/certified/{id}/run}, and the {@code source: "certified"} attribution the issue's
 * acceptance criterion asks for on the ask path.
 */
public class AiCertifiedQueryResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AiQueryResource resource;
    private CertifiedQueryRegistry registry;

    private static final String ENTRY = "{\"id\":\"monthly-net-revenue\","
            + "\"description\":\"Official monthly net revenue.\","
            + "\"matchIntent\":[\"monthly revenue\",\"revenue by month\"],"
            + "\"query\":{\"name\":\"certified-monthly\",\"cube\":{\"name\":\"Sales\",\"connection\":\"conn\"},"
            + "\"type\":\"MDX\",\"mdx\":\"SELECT {} ON COLUMNS FROM [Sales]\"}}";

    @Before
    public void setUp() throws Exception {
        Path root = tmp.newFolder("certified").toPath();
        Files.write(root.resolve("monthly-net-revenue.json"), ENTRY.getBytes(StandardCharsets.UTF_8));
        // A second, broken file so the ?errors=true surface is exercised too.
        Files.write(root.resolve("broken.json"), "{".getBytes(StandardCharsets.UTF_8));
        registry = new CertifiedQueryRegistry(root);

        AiSchema schema = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        resource = new AiQueryResource();
        resource.setCubeMetadataService(ref -> schema);
        resource.setThinQueryService(new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                return AiQueryResourceTest.buildStubCellDataSet();
            }
        });
        resource.setCertifiedQueries(registry);
    }

    @Test
    public void listReturnsSummariesWithoutTheQueryBody() {
        Response resp = resource.listCertified(false);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("certified");
        assertEquals(1, entries.size());
        assertEquals("monthly-net-revenue", entries.get(0).get("id"));
        assertNotNull(entries.get(0).get("description"));
        assertNotNull(entries.get(0).get("matchIntent"));
        assertNull(
                "the approved MDX must not leak from the catalogue",
                entries.get(0).get("query"));
        assertNull(body.get("errors"));
    }

    @Test
    public void listSurfacesParseErrorsOnRequest() {
        Response resp = resource.listCertified(true);
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertEquals(1, errors.size());
        assertEquals("MALFORMED_JSON", errors.get(0).get("code"));
    }

    @Test
    public void getOneIncludesTheQueryBody() {
        Response resp = resource.getCertified("monthly-net-revenue");
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertNotNull("the operator reviewing an approval needs the actual query", body.get("query"));
    }

    @Test
    public void getUnknownIs404() {
        assertEquals(404, resource.getCertified("nope").getStatus());
    }

    @Test
    public void runExecutesVerbatimAndAttributesTheResponse() {
        Response resp = resource.runCertified("monthly-net-revenue", "records");
        assertEquals(200, resp.getStatus());
        AiQueryResponse body = (AiQueryResponse) resp.getEntity();
        assertEquals(AiQueryResponse.Status.SUCCESS, body.getStatus());
        assertEquals("certified", body.getSource());
        assertEquals("monthly-net-revenue", body.getCertifiedId());
        assertNotNull(body.getData());
    }

    @Test
    public void runUnknownIs404() {
        assertEquals(404, resource.runCertified("nope", "records").getStatus());
    }

    @Test
    public void runWithoutRegistryIs404() {
        resource.setCertifiedQueries(null);
        assertEquals(
                404, resource.runCertified("monthly-net-revenue", "records").getStatus());
        Response list = resource.listCertified(false);
        assertEquals(200, list.getStatus());
    }

    @Test
    public void askCarriesSourceCertified() throws Exception {
        // The acceptance criterion, at the layer a client actually calls: an ask whose intent
        // matches a certified query comes back attributed, with no model-authored request.
        wireCertifiedAskService();
        AiAskApi.AskRequest body = new AiAskApi.AskRequest();
        body.setQuestion("what was monthly revenue?");
        body.setCube(new AiCubeRef("conn", "cat", "sch", "Sales"));

        Response resp = resource.ask(body);
        assertEquals(200, resp.getStatus());
        AiAskApi.AskResponse askResp = (AiAskApi.AskResponse) resp.getEntity();
        assertFalse(askResp.isDegraded());
        assertNotNull(askResp.getResponse());
        assertEquals("certified", askResp.getResponse().getSource());
        assertEquals("monthly-net-revenue", askResp.getResponse().getCertifiedId());
        assertNull("no model-authored query to hydrate a canvas from", askResp.getRequest());
    }

    @Test
    public void streamingAskAlsoAttributesCertified() throws Exception {
        String frame = streamOutcome(certifiedOutcome());
        assertTrue(frame, frame.contains("\"kind\":\"CERTIFIED\""));
        assertTrue(frame, frame.contains("\"source\":\"certified\""));
        assertTrue(frame, frame.contains("\"certifiedId\":\"monthly-net-revenue\""));
    }

    @Test
    public void refreshReportsCounts() {
        Response resp = resource.refreshCertified();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(1, body.get("certified"));
        assertEquals(1, body.get("errors"));
    }

    private AiAskService.AskOutcome certifiedOutcome() {
        try {
            return AiAskService.AskOutcome.okCertified(CertifiedQueryParser.parse("test", ENTRY));
        } catch (CertifiedQueryParser.ParseException e) {
            throw new IllegalStateException("test fixture must parse", e);
        }
    }

    private String streamOutcome(AiAskService.AskOutcome outcome) throws Exception {
        java.io.StringWriter sw = new java.io.StringWriter();
        resource.streamOutcomeAsSse(outcome, new AiQueryResource.SseWriter(sw));
        return sw.toString();
    }

    private void wireCertifiedAskService() {
        resource.setAskService(
                new AiAskService(ref -> new AiSchema("c/c/c/Sales", "Sales", "[Sales]"), req -> {
                    throw new AssertionError("the provider must not be consulted for a certified match");
                }) {
                    @Override
                    public AskOutcome ask(
                            AiCubeRef ref,
                            String question,
                            List<NlAskMessage> history,
                            String cellsetDigest,
                            NlAskRequest.ForceTool forceTool,
                            AiQueryRequest currentQuery) {
                        return certifiedOutcome();
                    }
                });
    }

    @Test
    public void certifiedRunIsNotAttributedWhenItIsADerivedAnswer() {
        // Guard against the opposite regression: a normal model-authored ask must NOT claim
        // certification.
        Response resp = resource.ask(derivedAsk());
        assertEquals(200, resp.getStatus());
        AiAskApi.AskResponse body = (AiAskApi.AskResponse) resp.getEntity();
        assertNotNull(body.getResponse());
        assertNull(body.getResponse().getSource());
        assertNull(body.getResponse().getCertifiedId());
    }

    private AiAskApi.AskRequest derivedAsk() {
        wireDerivedAskService();
        AiAskApi.AskRequest body = new AiAskApi.AskRequest();
        body.setQuestion("how many units did we ship?");
        body.setCube(new AiCubeRef("conn", "cat", "sch", "Sales"));
        return body;
    }

    private void wireDerivedAskService() {
        AiSchema schema = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        schema.measures.put(
                AiSchema.key("Store Sales"), new AiSchema.Measure("Store Sales", "[Measures].[Store Sales]"));
        resource.setCubeMetadataService(ref -> schema);
        resource.setAskService(
                new AiAskService(ref -> schema, req -> {
                    throw new AssertionError("stubbed below");
                }) {
                    @Override
                    public AskOutcome ask(
                            AiCubeRef ref,
                            String question,
                            List<NlAskMessage> history,
                            String cellsetDigest,
                            NlAskRequest.ForceTool forceTool,
                            AiQueryRequest currentQuery) {
                        AiQueryRequest req = new AiQueryRequest();
                        req.setCube(new AiCubeRef("conn", "cat", "sch", "Sales"));
                        req.setMeasures(java.util.Collections.singletonList(
                                new org.saiku.service.olap.ai.AiMeasureSelection("Store Sales")));
                        return AskOutcome.ok(req, "claude-x");
                    }
                });
    }
}
