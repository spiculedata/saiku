/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import org.saiku.web.security.oauth.OAuthResourceServerProperties;

/**
 * Acceptance criterion (saiku#879): {@code GET
 * /rest/saiku/mcp/.well-known/oauth-protected-resource} returns valid resource-server metadata when
 * {@code saiku.oauth.issuerUri} is configured, and 404 when it isn't.
 */
public class McpOAuthProtectedResourceResourceTest {

    @Test
    public void returns_404_when_oauth_not_configured() {
        McpOAuthProtectedResourceResource resource = new McpOAuthProtectedResourceResource();
        resource.setProperties(OAuthResourceServerProperties.from(new Properties()));

        Response response = resource.metadata(null);

        assertEquals(404, response.getStatus());
    }

    @Test
    public void returns_metadata_when_oauth_configured() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com/realms/saiku");
        raw.setProperty(OAuthResourceServerProperties.PROP_RESOURCE, "https://saiku.example.com/rest/saiku/api/mcp");

        McpOAuthProtectedResourceResource resource = new McpOAuthProtectedResourceResource();
        resource.setProperties(OAuthResourceServerProperties.from(raw));

        Response response = resource.metadata(null);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("https://saiku.example.com/rest/saiku/api/mcp", body.get("resource"));
        assertEquals(List.of("https://idp.example.com/realms/saiku"), body.get("authorization_servers"));
        assertEquals(List.of("header"), body.get("bearer_methods_supported"));
        assertEquals(List.of("mcp:query"), body.get("scopes_supported"));
    }

    @Test
    public void derive_resource_url_returns_null_without_a_request() {
        assertNull(McpOAuthProtectedResourceResource.deriveResourceUrl(null));
    }
}
