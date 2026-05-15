/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiQueryResponse;

/**
 * saiku#791 — pin the Jackson → AiQueryResponse 400 envelope.
 */
public class JacksonValidationExceptionMapperTest {

    private final JacksonValidationExceptionMapper mapper = new JacksonValidationExceptionMapper();

    @Test
    public void unrecognizedPropertyMapsToTypedEnvelopeWithSortedAvailable() throws Exception {
        // Drive the real Jackson path so we get a fully-populated UPE with a
        // live JsonParser context. Synthetic UPE.from(null, ...) NPEs out of
        // currentLocation() — not the path the production code ever sees.
        ObjectMapper om = new ObjectMapper();
        String wire = "{\"foobar\":\"typo\"}";
        UnrecognizedPropertyException upe;
        try {
            om.readValue(wire, AiQueryRequest.class);
            throw new AssertionError("expected UPE from real-Jackson parse");
        } catch (UnrecognizedPropertyException caught) {
            upe = caught;
        }

        Response r = mapper.toResponse(upe);

        assertEquals(400, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());

        AiQueryResponse body = (AiQueryResponse) r.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertEquals("foobar", body.getField());
        assertNotNull(body.getError());
        assertTrue("error names the bad field", body.getError().contains("foobar"));
        assertTrue("error names the type", body.getError().contains("AiQueryRequest"));

        // Known fields list is sorted so consumers can rely on stable
        // ordering — Jackson's underlying Set has no guarantee.
        java.util.List<String> avail = body.getAvailable();
        assertNotNull(avail);
        java.util.List<String> sorted = new java.util.ArrayList<>(avail);
        java.util.Collections.sort(sorted);
        assertEquals("available[] is already sorted", sorted, avail);
        assertTrue("available[] contains known fields", avail.contains("cube"));
        assertTrue("available[] contains known fields", avail.contains("rows"));
        assertTrue("available[] contains known fields", avail.contains("measures"));
    }

    @Test
    public void messageStripsSourceLocationNoise() {
        JsonMappingException jme = new JsonMappingException(
                (JsonParser) null,
                "Cannot deserialize value of type `int` from String \"abc\"\n at [Source: REDACTED;"
                        + " line: 1, column: 42]");
        Response r = mapper.toResponse(jme);
        AiQueryResponse body = (AiQueryResponse) r.getEntity();
        assertNotNull(body.getError());
        assertFalse("source location stripped", body.getError().contains("Source"));
        assertFalse("line/column noise stripped", body.getError().contains("line: 1"));
        assertTrue("original message preserved", body.getError().startsWith("Cannot deserialize"));
    }

    @Test
    public void fieldPathBuiltFromRealJacksonParse() {
        // Drive the real Jackson path: deserialise a payload with an unknown
        // top-level field, catch the exception, run through the mapper.
        ObjectMapper om = new ObjectMapper();
        String body = "{\"foobar\":\"typo\"}";
        try {
            om.readValue(body, AiQueryRequest.class);
        } catch (JsonMappingException e) {
            Response r = mapper.toResponse(e);
            AiQueryResponse env = (AiQueryResponse) r.getEntity();
            assertEquals("foobar", env.getField());
            assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, env.getStatus());
            return;
        } catch (Exception other) {
            throw new AssertionError("expected JsonMappingException, got " + other.getClass(), other);
        }
        throw new AssertionError("expected the unknown-field parse to throw");
    }

    @Test
    public void noPathReturnsNullField() {
        // Defensive: a JsonMappingException with no path should not crash and
        // should not produce a stale "field" value on the wire.
        JsonMappingException jme = new JsonMappingException((JsonParser) null, "boom");
        Response r = mapper.toResponse(jme);
        AiQueryResponse body = (AiQueryResponse) r.getEntity();
        assertEquals(AiQueryResponse.Status.VALIDATION_ERROR, body.getStatus());
        assertNull(body.getField());
    }
}
