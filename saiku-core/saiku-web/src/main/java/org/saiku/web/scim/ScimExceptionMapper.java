/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a {@link ScimException} as the RFC 7644 §3.12 {@code Error} body.
 *
 * <p>Registered ahead of the app's catch-all {@code ExceptionMapper<Throwable>}, which would
 * otherwise pass the bare {@code Response} through with an empty body — legal HTTP, but IdPs
 * surface a bodyless failure as a generic "SCIM error" and lose the {@code scimType} that tells
 * them whether the assignment is a uniqueness conflict or a hard rejection.
 *
 * <p>{@code detail} is authored to be operator-facing and free of internals (no SQL, no class
 * names, no filesystem paths); anything unexpected is logged server-side and reported as a plain
 * 500 so nothing leaks on the wire.
 */
@Provider
public class ScimExceptionMapper implements ExceptionMapper<ScimException> {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionMapper.class);

    @Override
    public Response toResponse(ScimException e) {
        int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
        if (status >= 500) {
            log.error("SCIM request failed with status {}", status, e);
        } else {
            log.debug("SCIM request rejected: {} {} {}", status, e.getScimType(), e.getDetail());
        }
        return Response.status(status)
                .entity(e.toScimError())
                .type(MediaType.valueOf(ScimResponses.SCIM_JSON))
                .build();
    }
}
