/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * A SCIM-protocol failure carrying both the HTTP status and the RFC 7644 {@code scimType}.
 *
 * <p>IdP connectors branch on {@code scimType} ({@code uniqueness}, {@code mutability},
 * {@code invalidValue}, {@code invalidFilter}, {@code noTarget}) to decide whether a user
 * assignment is a conflict, a hard rejection, or a retry — so the type is load-bearing, not
 * decoration. {@link #detail} is operator-facing but must never carry internals (SQL text,
 * class names, file paths); callers phrase it accordingly.
 */
public class ScimException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    private final String scimType;
    private final String detail;

    public ScimException(int status, String scimType, String detail) {
        super(Response.status(status).build());
        this.scimType = scimType;
        this.detail = detail;
    }

    public String getScimType() {
        return scimType;
    }

    public String getDetail() {
        return detail;
    }

    public ScimError toScimError() {
        return new ScimError(String.valueOf(getResponse().getStatus()), scimType, detail);
    }

    public static ScimException badRequest(String scimType, String detail) {
        return new ScimException(400, scimType, detail);
    }

    public static ScimException notFound(String detail) {
        return new ScimException(404, null, detail);
    }

    public static ScimException conflict(String scimType, String detail) {
        return new ScimException(409, scimType, detail);
    }
}
