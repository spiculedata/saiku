/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.ai.OssieAiAskService;

/**
 * saiku#1918 (17c, CWE-74) — the edge half of the Ossie role gate.
 *
 * <p>{@link OssieChatTurnRoleTest} covers the transport seam, which coerces anything unknown to
 * {@code user}. This covers the layer above it: a caller who sends {@code role:"system"} should be
 * told so, with a 400 that names the legal values, rather than having the turn silently reinterpreted
 * as something they didn't write. The MDX ask path behaves the same way.
 *
 * <p>The check runs with the other shape guards, before any warehouse or model work, so these
 * tests need nothing wired beyond a "configured" ask service.
 */
public class AiOssieRoleGuardTest {

    private AiOssieResource resource;

    @Before
    public void setUp() {
        resource = new AiOssieResource();
        resource.setAskService(new OssieAiAskService() {
            @Override
            public boolean isConfigured() {
                return true;
            }
        });
    }

    private Response askWithHistory(Object role) {
        return resource.ask(Map.of(
                "connection", "sales",
                "model", "sales",
                "question", "and by channel?",
                "history", List.of(Map.of("role", role, "content", "show sales by region"))));
    }

    @Test
    public void systemRoleIsRejectedWith400() {
        Response resp = askWithHistory("system");
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void theRejectionListsTheLegalRoles() {
        // Self-correction, matching the VALIDATION_ERROR envelope's contract everywhere else on the
        // AI surface: the caller learns the fix from the 400 rather than by reading the docs.
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp400(askWithHistory("system"));
        assertEquals("VALIDATION_ERROR", entity.get("error"));
        assertTrue(String.valueOf(entity.get("field")).startsWith("history"));
        assertEquals(List.of("user", "assistant"), entity.get("available"));
    }

    @Test
    public void otherPrivilegedRoleNamesAreRejectedToo() {
        // "developer" and "tool" are not roles this surface speaks, and a provider that grows one
        // must not be reachable by guessing its name here.
        for (Object role : List.of("developer", "tool", "function", "model", "human", "root")) {
            assertEquals(
                    "role '" + role + "' must be refused",
                    400,
                    askWithHistory(role).getStatus());
        }
    }

    @Test
    public void theRoleCheckIsNotCaseSensitive() {
        // A chat UI that sends "User" is not an attack, and must not be told it is. Past the role
        // guard the request reaches the warehouse/model wiring, which is unwired in this unit test
        // — hence 500 rather than the 400 the role gate would have produced. What matters here is
        // that the status is NOT the role gate's 400.
        Response resp = askWithHistory("USER");
        assertTrue("must get past the role guard, got " + resp.getStatus(), resp.getStatus() != 400);
    }

    @Test
    public void theOffendingTurnIndexIsReported() {
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp400(askWithHistory("system"));
        assertTrue(
                "the message must point at the offending turn, got: " + entity.get("message"),
                String.valueOf(entity.get("message")).contains("history[0].role"));
    }

    /** Unwrap the JSON entity, tolerating either a {@code Map} or an already-typed body. */
    private static Object resp400(Response resp) {
        assertEquals(400, resp.getStatus());
        return resp.getEntity();
    }
}
