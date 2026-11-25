/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;

/**
 * Maps the counted request stream's {@link RestBodySizeFilter.TooLargeException}
 * to the same clean {@code 413} the {@code Content-Length} pre-check produces
 * (issue #2535). The exception is thrown from the wrapped entity stream while
 * Jersey is reading a body that turned out to exceed its route cap — typically a
 * {@code Transfer-Encoding: chunked} body, which no header check can pre-reject.
 *
 * <p>Jersey picks the most specific mapper for the exception type, so this wins
 * over the catch-all {@code GenericExceptionMapper} ({@code ExceptionMapper<
 * Throwable>}) and the refusal stays a 413 instead of a generic 500.
 */
public class RestBodyTooLargeMapper implements ExceptionMapper<RestBodySizeFilter.TooLargeException> {

    @Override
    public Response toResponse(RestBodySizeFilter.TooLargeException e) {
        return Response.status(413)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "request too large"))
                .build();
    }
}
