/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.saiku.service.olap.ai.AiQueryResponse;

/**
 * saiku#791 — translate Jackson deserialisation failures into the typed
 * {@link AiQueryResponse} 400 envelope used by every other validation path
 * on the AI Query API.
 *
 * <p>Without this provider, an unknown field on the request body (typo, old
 * client, missing key) returns an HTTP 400 with raw Jackson text in
 * {@code text/plain} — class names, source locations, the
 * {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} implementation
 * detail. Agents that parse the JSON contract (status / field / error /
 * available[]) for every other validation case have to special-case this
 * one path. Worse, the message leaks server-internal class names.
 *
 * <p>This mapper catches {@link JsonMappingException} (and the
 * {@code UnrecognizedProperty} subclass), pulls the field path from the
 * {@code referencePath}, and returns a clean
 * {@code 400 application/json} body in the same {@link AiQueryResponse}
 * shape so the agent's existing error-handling code Just Works.
 *
 * <p>Scoped to JSON-shape errors only — domain validation continues to flow
 * through {@code AiValidationException} in the resources, which has more
 * context than Jackson can provide.
 */
@Provider
public class JacksonValidationExceptionMapper implements ExceptionMapper<JsonMappingException> {

    @Override
    public Response toResponse(JsonMappingException e) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.VALIDATION_ERROR);

        String field = fieldFromPath(e);
        if (field != null) resp.setField(field);

        if (e instanceof UnrecognizedPropertyException) {
            UnrecognizedPropertyException upe = (UnrecognizedPropertyException) e;
            // Pulled from the deserialiser's known-properties list. Sorted so
            // the response is stable across JVMs (Jackson uses a Set internally).
            List<String> known = new ArrayList<>();
            Collection<Object> ids = upe.getKnownPropertyIds();
            if (ids != null) {
                for (Object id : ids) known.add(String.valueOf(id));
            }
            java.util.Collections.sort(known);
            resp.setAvailable(known);
            String propName = upe.getPropertyName();
            String typeName =
                    upe.getReferringClass() != null ? upe.getReferringClass().getSimpleName() : "request";
            resp.setError("Unknown field '" + propName + "' on " + typeName
                    + (known.isEmpty() ? "." : ". Known fields: " + String.join(", ", known) + "."));
        } else {
            // Non-unknown-field JSON mapping failure (type mismatch, bad enum
            // value, malformed nested structure). The Jackson message is the
            // useful part — strip the source-location noise and ship the rest.
            resp.setError(cleanMessage(e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage()));
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(resp)
                .build();
    }

    /** Build a dotted/bracketed field path from Jackson's referencePath.
     *  Example: {@code rows[0].members[2]}. */
    private static String fieldFromPath(JsonMappingException e) {
        List<JsonMappingException.Reference> path = e.getPath();
        if (path == null || path.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : path) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) sb.append('.');
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Strip Jackson's location suffix ({@code " at [Source: ...]"}) which
     *  leaks the {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION}
     *  implementation detail. */
    private static String cleanMessage(String msg) {
        if (msg == null) return null;
        int at = msg.indexOf("\n at [Source");
        if (at >= 0) return msg.substring(0, at).trim();
        at = msg.indexOf(" at [Source");
        if (at >= 0) return msg.substring(0, at).trim();
        return msg;
    }
}
