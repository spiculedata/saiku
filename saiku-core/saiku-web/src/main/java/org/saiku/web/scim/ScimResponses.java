/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import jakarta.ws.rs.core.Response;
import org.saiku.web.security.scim.ScimAuthFilter;

/** Response helpers shared by the SCIM resources: the media type IdPs expect, and the auth guard. */
public final class ScimResponses {

    private ScimResponses() {}

    /**
     * RFC 7644 §3.1 defines {@code application/scim+json}. Connectors accept it; the generic
     * exception mapper in this app emits {@code application/json}, so a failure path is
     * normalised here to keep a SCIM client's content-type expectations intact.
     */
    public static final String SCIM_JSON = "application/scim+json;charset=UTF-8";

    /**
     * Defence in depth behind {@link ScimAuthFilter}: the filter already 401s an absent or
     * unknown bearer, so reaching a resource method without a SCIM principal means the filter
     * chain was misrouted. Fail closed rather than serving a user directory to it.
     */
    public static ScimToken requireScimPrincipal() {
        ScimToken token = ScimAuthFilter.currentToken();
        if (token == null) {
            throw new ScimException(401, null, "A valid SCIM bearer token is required");
        }
        return token;
    }

    public static Response ok(Object body) {
        return Response.ok(body).type(SCIM_JSON).build();
    }

    public static Response created(Object body, String location) {
        return Response.status(Response.Status.CREATED)
                .location(java.net.URI.create(location))
                .entity(body)
                .type(SCIM_JSON)
                .build();
    }

    public static Response noContent() {
        return Response.noContent().build();
    }

    /** SCIM {@code ListResponse} with the paging headers some connectors read instead of the body. */
    public static Response list(ScimListResponse body) {
        return Response.ok(body)
                .type(SCIM_JSON)
                .header("X-Total-Count", body.totalResults)
                .header("X-Items-Per-Page", body.itemsPerPage)
                .header("X-Start-Index", body.startIndex)
                .build();
    }
}
