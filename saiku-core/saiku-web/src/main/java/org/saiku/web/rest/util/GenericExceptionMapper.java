/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.saiku.service.olap.ai.AiValidationException;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#1165 (audit-3) — global JAX-RS {@link ExceptionMapper} that prevents
 * resource methods from ever leaking Mondrian/SQL/path/class internals to the
 * wire.
 *
 * <p>Historically several resources caught a {@code Throwable} and returned
 * {@code e.getMessage()}, {@code ExceptionUtils.getRootCauseMessage(e)}, or —
 * worst of all in {@code QueryResource} — {@code entity(e)}, which serialised
 * the entire exception object (message, cause chain, sometimes stack frames)
 * straight into the HTTP body. That hands an attacker a free map of the
 * server's internals (driver classes, JDBC URLs, filesystem paths, fork
 * version strings).
 *
 * <p>This mapper logs the full exception <em>server-side</em> with a generated
 * correlation id and returns a fixed, information-free JSON body:
 *
 * <pre>{@code
 * { "status": "ERROR", "error": "Internal error", "ref": "<uuid>" }
 * }</pre>
 *
 * <p>The {@code ref} is the only thing shared with the client; operators grep
 * the logs for it to find the corresponding stack trace.
 *
 * <h2>What it must NOT swallow</h2>
 *
 * <ul>
 *   <li>{@link WebApplicationException} — resources (and the more-specific
 *       mappers) build typed responses through it; we hand the existing
 *       {@code Response} straight back so 404s, redirects, etc. survive.</li>
 *   <li>{@link AiValidationException} — the AI Query API's structured
 *       {@code {field, available}} 400 envelope. Resources normally catch this
 *       in-method, but if one ever propagates we preserve its self-correction
 *       data rather than collapsing it into the opaque 500.</li>
 *   <li>{@link JsonMappingException} — deferred to the more-specific
 *       {@link org.saiku.web.rest.exception.JacksonValidationExceptionMapper}
 *       (saiku#791); if one still reaches here it is a 400, not a 500.</li>
 *   <li>{@link NullPointerException} — the dominant missing-required-param
 *       case on {@code BasicRepositoryResource2}; surfaced as a 400 client
 *       error with a generic message (preserves saiku#865 behaviour without
 *       leaking the NPE detail).</li>
 *   <li>{@link SaikuAccessDeniedException} — saiku#1973: the caller IS
 *       authenticated but is not authorised for the datasource they named
 *       (the #1968 fail-closed denial). That is a 403, not one of our faults,
 *       so it gets its own fixed, information-free envelope instead of the
 *       opaque 500 — which was the tell-tale sign of a server fault rather
 *       than an authorization decision.</li>
 * </ul>
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        // 1) Typed JAX-RS responses pass straight through untouched.
        if (t instanceof WebApplicationException wae) {
            return wae.getResponse();
        }

        // 2) AI Query API structured validation error — preserve the
        //    {field, available} self-correction envelope; never collapse it
        //    into the opaque 500. (Belt-and-suspenders: resources usually
        //    catch this themselves.)
        if (t instanceof AiValidationException ave) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "VALIDATION_ERROR");
            if (ave.getField() != null) {
                body.put("field", ave.getField());
            }
            body.put("error", ave.getMessage());
            List<String> available = ave.getAvailable();
            body.put("available", available == null ? new ArrayList<>() : new ArrayList<>(available));
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body)
                    .build();
        }

        // 3) Defer JSON-shape failures to the dedicated Jackson mapper
        //    (saiku#791). It ranks more-specific so JAX-RS should pick it
        //    first; if one still arrives here treat it as a generic 400.
        if (t instanceof JsonMappingException) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "BAD_REQUEST");
            body.put("field", "request");
            body.put("error", "Malformed request body");
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body)
                    .build();
        }

        // 4) Missing/blank required params land here as NPEs from the
        //    resource bodies. A missing required field is a client error
        //    (saiku#865); surface as 400 with a generic message — no detail.
        if (t instanceof NullPointerException) {
            log.debug("Resource NPE, likely missing param", t);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "BAD_REQUEST");
            body.put("field", "request");
            body.put("error", "Required parameter missing or null");
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body)
                    .build();
        }

        // 5) saiku#1973 — access denied on a datasource (saiku#1968 fail-closed
        //    denial) is an authorization decision, not an internal fault: 403
        //    with a fixed body. The thrown message names the datasource, which
        //    the caller has no business learning from the denial path, so it is
        //    logged server-side and never echoed.
        SaikuAccessDeniedException denied = findAccessDenied(t);
        if (denied != null) {
            log.warn("Access denied on datasource [detail={}]", denied.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "FORBIDDEN");
            body.put("error", "Access denied");
            return Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(body)
                    .build();
        }

        // 6) Everything else: log the full stack server-side keyed by a
        //    correlation id and return an information-free 500. The body
        //    carries no message, class name, cause chain, or stack.
        String ref = UUID.randomUUID().toString();
        log.error("Unhandled resource exception [ref={}]", ref, t);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("error", "Internal error");
        body.put("ref", ref);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    /**
     * Walk the cause chain for a {@link SaikuAccessDeniedException} (saiku#1973).
     *
     * <p>By exception TYPE, never by message text — the denial message is the one string we are
     * explicitly not honouring. The walk matters because a resource (or an AOP proxy) may rewrap the
     * denial before it reaches the mapper, and a denial that degraded into an opaque 500 purely
     * because of a wrapper is exactly the inconsistency this issue is about. Self-referencing cause
     * chains terminate the loop.
     */
    static SaikuAccessDeniedException findAccessDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SaikuAccessDeniedException denied) {
                return denied;
            }
        }
        return null;
    }
}
