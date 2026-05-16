/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#865 — catch any otherwise-unhandled {@link Throwable} that propagates
 * out of a JAX-RS resource method and emit a JSON envelope instead of letting
 * Jersey hand it back to Jetty's default {@code ErrorHandler} (which renders
 * an HTML page complete with a {@code Powered by Jetty:// 12.0.32} banner —
 * a free server-fingerprint for attackers and a useless body for JSON
 * clients).
 *
 * <p>Specifically targets {@code BasicRepositoryResource2}'s missing-param
 * NPEs and any other resource that throws an unexpected runtime exception.
 * Domain errors with their own typed envelopes
 * ({@code AiValidationException}, {@link WebApplicationException}) bypass
 * this mapper — the {@code WebApplicationException} check below preserves
 * the typed responses they emit (e.g.,
 * {@link org.saiku.web.rest.resources.OlapDiscoverResource}'s 404s).
 *
 * <p>The {@code JsonMappingException} check defers to the more-specific
 * {@link JacksonValidationExceptionMapper} (saiku#791).
 */
@Provider
public class GenericFailureExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(GenericFailureExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        // Preserve the typed envelopes resources already build.
        if (t instanceof WebApplicationException wae) {
            return wae.getResponse();
        }
        // Defer to the more-specific Jackson mapper.
        if (t instanceof JsonMappingException) {
            // The JacksonValidationExceptionMapper is registered first and
            // ranks more-specific, so JAX-RS should pick it before this one.
            // If we still see one here, treat it the same as a 400.
            return jsonEnvelope(
                    Response.Status.BAD_REQUEST, "BAD_REQUEST", "request", "Malformed request body", safeMessage(t));
        }
        // NullPointerException is the dominant case for missing form/query
        // params on BasicRepositoryResource2. Surface as 400, not 500 — a
        // missing required field is a client error.
        if (t instanceof NullPointerException) {
            log.debug("Resource NPE, likely missing param", t);
            return jsonEnvelope(
                    Response.Status.BAD_REQUEST, "BAD_REQUEST", "request", "Required parameter missing or null", null);
        }
        // Anything else: log the stack server-side, return a clean 500
        // envelope. Never include the stack trace in the response body.
        log.error("Unhandled resource exception", t);
        return jsonEnvelope(
                Response.Status.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                null,
                "Request failed: " + t.getClass().getSimpleName(),
                safeMessage(t));
    }

    private static Response jsonEnvelope(
            Response.Status status, String tag, String field, String message, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", tag);
        if (field != null) body.put("field", field);
        body.put("error", message);
        if (detail != null) body.put("detail", detail);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    /** Strip nothing — but cap length so we don't echo a megabyte stack message back. */
    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) return null;
        return m.length() > 500 ? m.substring(0, 500) + "…" : m;
    }
}
