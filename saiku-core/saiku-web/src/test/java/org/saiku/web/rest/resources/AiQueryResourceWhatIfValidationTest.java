/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;

/**
 * saiku#2550j — the what-if endpoint concatenates client identifiers into MDX and
 * mutates per-connection scenario state, so every name is validated BEFORE it can
 * reach a query and the whole scenario sequence is serialised per connection. This
 * pins the validation gate: a name that doesn't parse as a full MDX unique name
 * (or a member that isn't on the level) is a 400 and NEVER touches the connection.
 */
public class AiQueryResourceWhatIfValidationTest {

    private AiQueryResource resource;

    @Before
    public void setUp() {
        resource = new AiQueryResource();
        resource.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));
        // The connection must never be reached when validation rejects a name.
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public org.olap4j.OlapConnection getNativeConnection(String name) {
                throw new AssertionError("a rejected request must never touch the connection");
            }
        });
    }

    private static Map<String, Object> body(String measure, String level, String member) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("connection", "foodmart");
        b.put("cube", "Sales");
        b.put("measure", measure);
        b.put("level", level);
        b.put("member", member);
        b.put("value", 100);
        return b;
    }

    private static void assertBadRequestField(Response resp, String field) throws Exception {
        assertEquals(400, resp.getStatus());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp.getEntity());
        assertTrue("expected a " + field + " rejection, got " + json, json.contains("\"field\":\"" + field + "\""));
    }

    @Test
    public void validNames_passValidation_andOnlyThenReachTheConnection() {
        // Control: names in the right shape get PAST validation (and then fail
        // naturally when the (stub) connection lookup throws, which is the
        // assertion the other tests rely on never happening).
        try {
            resource.scenarioWhatIf(body("Store Sales", "[Time].[Time]", "[Time].[Time].[1997]"));
        } catch (AssertionError reachedConnection) {
            // expected — validation passed and the (never-wired) connection was touched
            return;
        }
        throw new AssertionError("expected the stubbed connection to be reached");
    }

    @Test
    public void measureIsAPlainName_noBrackets() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("[Measures].[X]] --", "[Time].[Time]", "[Time].[Time].[1997]")),
                "measure");
    }

    @Test
    public void cubeIsAPlainName_noBrackets() throws Exception {
        Map<String, Object> b = body("Store Sales", "[Time].[Time]", "[Time].[Time].[1997]");
        b.put("cube", "Sales] ON ROWS FROM [Other");
        assertBadRequestField(resource.scenarioWhatIf(b), "cube");
    }

    @Test
    public void levelMustBeAUniqueName() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("Store Sales", "Time.Members]] --", "[Time].[Time].[1997]")), "level");
    }

    @Test
    public void memberMustBeAUniqueName() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("Store Sales", "[Time].[Time]", "[Time].[Time]] --")), "member");
    }

    @Test
    public void memberMustBeOnTheLevel() throws Exception {
        // Right shape, wrong level — [Store].[Store].[USA] is not on [Time].[Time].
        assertBadRequestField(
                resource.scenarioWhatIf(body("Store Sales", "[Time].[Time]", "[Store].[Store].[USA]")), "member");
    }
}
