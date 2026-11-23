/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.saiku.service.olap.ai.AiValidationException;
import org.saiku.service.util.exception.SaikuAccessDeniedException;

/**
 * saiku#1165 (audit-3) — pin the global error envelope so a resource can never
 * leak Mondrian/SQL/path/class internals back to the client.
 */
public class GenericExceptionMapperTest {

    private final GenericExceptionMapper mapper = new GenericExceptionMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    public void genericRuntimeMapsToOpaque500WithRefAndNoMessage() {
        // A runtime exception whose message would, pre-fix, leak straight onto
        // the wire (driver class, JDBC URL, filesystem path, etc.).
        String secret = "mondrian.olap.MondrianException: jdbc:h2:/srv/saiku/foodmart;PWD=hunter2";
        RuntimeException boom = new RuntimeException(secret);

        Response r = mapper.toResponse(boom);

        assertEquals(500, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());

        Map<String, Object> body = bodyOf(r);
        assertEquals("ERROR", body.get("status"));
        assertEquals("Internal error", body.get("error"));

        // A correlation id is present and is a real UUID.
        Object ref = body.get("ref");
        assertNotNull("ref correlation id present", ref);
        UUID.fromString(ref.toString()); // throws if not a UUID -> test fails

        // Nothing internal leaks: no original message, no class name, no stack
        // anywhere in the serialised body.
        String rendered = body.toString();
        assertFalse("must not echo the exception message", rendered.contains(secret));
        assertFalse("must not echo the JDBC url", rendered.contains("jdbc:h2"));
        assertFalse("must not echo the password", rendered.contains("hunter2"));
        assertFalse("must not echo the class name", rendered.contains("MondrianException"));
        assertFalse("must not echo the class name", rendered.contains("RuntimeException"));
        assertNull("no detail field", body.get("detail"));
        assertNull("no message field", body.get("message"));
    }

    @Test
    public void aiValidationExceptionIsNotCollapsedIntoGenericEnvelope() {
        AiValidationException ave =
                new AiValidationException("cube", "Unknown cube 'Slaes'", Arrays.asList("Sales", "Warehouse"));

        Response r = mapper.toResponse(ave);

        // Preserved as the structured 400 self-correction envelope, NOT the
        // opaque 500.
        assertEquals(400, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());

        Map<String, Object> body = bodyOf(r);
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("field preserved for self-correction", "cube", body.get("field"));
        assertEquals("Unknown cube 'Slaes'", body.get("error"));
        assertTrue("available[] preserved", body.get("available") instanceof java.util.List);
        java.util.List<?> avail = (java.util.List<?>) body.get("available");
        assertTrue(avail.contains("Sales"));
        assertTrue(avail.contains("Warehouse"));

        // Crucially it is NOT the generic envelope.
        assertFalse("ERROR".equals(body.get("status")));
        assertNull("no opaque ref on a validation error", body.get("ref"));
    }

    @Test
    public void webApplicationExceptionPassesThroughUntouched() {
        Response typed = Response.status(404).entity("not found here").build();
        WebApplicationException wae = new WebApplicationException(typed);

        Response r = mapper.toResponse(wae);

        // Same Response instance handed straight back — the resource's typed
        // envelope (404s, redirects, etc.) survives the global mapper.
        assertEquals(404, r.getStatus());
        assertEquals("typed entity preserved", "not found here", r.getEntity());
    }

    @Test
    public void nullPointerSurfacesAsGeneric400NotLeaky500() {
        // saiku#865 behaviour preserved: a missing required param NPE is a
        // client error, surfaced generically.
        NullPointerException npe = new NullPointerException("queryName was null at QueryResource.line131");

        Response r = mapper.toResponse(npe);

        assertEquals(400, r.getStatus());
        Map<String, Object> body = bodyOf(r);
        assertEquals("BAD_REQUEST", body.get("status"));
        assertEquals("Required parameter missing or null", body.get("error"));
        assertFalse("no internal NPE detail leaks", body.toString().contains("QueryResource.line131"));
    }

    @Test
    public void everyGeneric500GetsAFreshRef() {
        Response a = mapper.toResponse(new IllegalStateException("a"));
        Response b = mapper.toResponse(new IllegalStateException("b"));
        Object refA = bodyOf(a).get("ref");
        Object refB = bodyOf(b).get("ref");
        assertNotNull(refA);
        assertNotNull(refB);
        if (refA.equals(refB)) {
            fail("each unhandled exception must get a distinct correlation id");
        }
    }

    /**
     * saiku#1973 — the saiku#1968 fail-closed datasource denial is an
     * authorization decision, so the global mapper answers 403 rather than
     * letting it masquerade as one of our internal faults.
     */
    @Test
    public void accessDeniedSurfacesAs403NotAnOpaque500() {
        Response r = mapper.toResponse(new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"sales_prod\"."));

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());

        Map<String, Object> body = bodyOf(r);
        assertEquals("FORBIDDEN", body.get("status"));
        assertEquals("Access denied", body.get("error"));
    }

    /** The denial names the datasource; the denial response must not. */
    @Test
    public void theForbiddenBodyNamesNoDatasource() {
        Response r = mapper.toResponse(new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"sales_prod\"."));

        String rendered = bodyOf(r).toString();
        assertFalse("datasource name leaked", rendered.contains("sales_prod"));
        assertNull(
                "no correlation ref needed on a 403 — it is not our fault",
                bodyOf(r).get("ref"));
    }

    /** A denial that arrives rewrapped (resource, AOP proxy) must not degrade into a 500. */
    @Test
    public void aRewrappedAccessDeniedIsStillForbidden() {
        Response r = mapper.toResponse(new RuntimeException(
                "outer",
                new IllegalStateException("middle", new SaikuAccessDeniedException("Access denied: ... sales_prod"))));

        assertEquals(403, r.getStatus());
        assertFalse("datasource name leaked", bodyOf(r).toString().contains("sales_prod"));
    }

    /** A self-referencing cause chain must not spin the denial walk forever. */
    @Test
    public void aSelfReferentialCauseChainTerminates() {
        RuntimeException loop = new RuntimeException("boom");
        SaikuAccessDeniedException denied = new SaikuAccessDeniedException("Access denied");
        // Force a cause cycle: loop → denied → wrapper → loop.
        RuntimeException wrapper = new RuntimeException("outer", loop);
        loop.initCause(denied);
        denied.initCause(wrapper);

        Response r = mapper.toResponse(loop);

        assertEquals(403, r.getStatus());
    }

    /** Everything else is unchanged — an ordinary fault is still an opaque 500. */
    @Test
    public void anOrdinaryFaultIsStillAnOpaque500() {
        Response r = mapper.toResponse(new IllegalStateException("jdbc:h2:/srv/saiku;PWD=hunter2"));

        assertEquals(500, r.getStatus());
        assertEquals("ERROR", bodyOf(r).get("status"));
        assertNotNull(bodyOf(r).get("ref"));
    }
}
