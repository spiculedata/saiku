/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import org.saiku.service.olap.ai.AiPolicyViolation;
import org.saiku.service.olap.ai.AiQueryResponse;

/**
 * saiku#903 — translate an {@link AiPolicyViolation} (the operator's
 * {@code ai.policy} tier forbids exposing this data kind) into a clean
 * {@code 403 application/json} body in the standard {@link AiQueryResponse}
 * envelope, so agents get a self-correcting, leak-free error instead of the
 * generic 500 the catch-all mapper would otherwise produce.
 *
 * <p>A specific {@code ExceptionMapper<AiPolicyViolation>} takes precedence over
 * the catch-all {@code ExceptionMapper<Throwable>} by JAX-RS specificity rules.
 * The body uses {@code field="ai.policy"} and {@code available=[<required tier>]}
 * so the caller knows exactly which knob to raise.
 */
@Provider
public class AiPolicyViolationMapper implements ExceptionMapper<AiPolicyViolation> {

    @Override
    public Response toResponse(AiPolicyViolation e) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setStatus(AiQueryResponse.Status.PERMISSION_DENIED);
        resp.setField("ai.policy");
        resp.setAvailable(List.of(e.getRequired().displayName()));
        resp.setError(e.getMessage()
                + " Set " + org.saiku.service.olap.ai.AiPolicy.PROP + "="
                + e.getRequired().displayName() + " (or higher) to permit it.");
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(resp)
                .build();
    }
}
