/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.web.security.oauth.OAuthResourceServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.0 Protected Resource Metadata for the native MCP endpoint (saiku#879, MCP spec
 * 2025-11-25 §8 / RFC 9728). Anonymous-accessible (wired {@code permitAll} in {@code
 * applicationContext-saiku.xml}) — an MCP client discovers which authorization server(s) issue
 * tokens for this resource BEFORE it has any credentials of its own.
 *
 * <p>404s when {@code saiku.oauth.issuerUri} isn't configured, so a #878-only (HTTP-Basic-only)
 * deployment simply doesn't advertise an OAuth path — there's nothing for a client to discover.
 */
@Path("/saiku/mcp/.well-known/oauth-protected-resource")
public class McpOAuthProtectedResourceResource {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthProtectedResourceResource.class);

    /** The single scope every MCP tool requires today (issue #879, "out of scope: per-tool
     *  scopes"). */
    static final String MCP_QUERY_SCOPE = "mcp:query";

    private OAuthResourceServerProperties properties;

    public void setProperties(OAuthResourceServerProperties properties) {
        this.properties = properties;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response metadata(@Context UriInfo uriInfo) {
        if (properties == null || !properties.isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String resource = properties.getResource();
        if (resource == null) {
            resource = deriveResourceUrl(uriInfo);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("authorization_servers", List.of(properties.getIssuerUri()));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("scopes_supported", List.of(MCP_QUERY_SCOPE));
        return Response.ok(body).build();
    }

    /** Derives the MCP endpoint's own canonical URL from the inbound request when {@code
     *  saiku.oauth.resource} isn't set explicitly. Package-visible for unit tests. */
    static String deriveResourceUrl(UriInfo uriInfo) {
        if (uriInfo == null) {
            return null;
        }
        try {
            URI base = uriInfo.getBaseUri();
            String scheme = base.getScheme();
            String host = base.getHost();
            int port = base.getPort();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443);
            if (port > 0 && !defaultPort) {
                sb.append(":").append(port);
            }
            sb.append("/rest/saiku/api/mcp");
            return sb.toString();
        } catch (RuntimeException e) {
            log.debug("Failed to derive MCP resource URL from request — set -Dsaiku.oauth.resource to override", e);
            return null;
        }
    }
}
