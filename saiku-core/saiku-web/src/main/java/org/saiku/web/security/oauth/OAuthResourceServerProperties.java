/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration for the MCP OAuth 2.0 resource-server surface (saiku#879). Inert by default — every
 * knob is read from system properties, and {@link #isEnabled()} is {@code false} unless {@link
 * #PROP_ISSUER_URI} is set, so an existing #878 HTTP-Basic-only deployment is untouched.
 *
 * <p>Launcher config surface (all {@code -D} system properties):
 *
 * <ul>
 *   <li>{@code saiku.oauth.issuerUri} — the IdP's OIDC issuer (Keycloak realm URL, Auth0 domain,
 *       Okta/Entra tenant). Required to turn the resource server on; JWKS + issuer/signature
 *       validation are discovered from this via OIDC discovery on first use.
 *   <li>{@code saiku.oauth.audience} — optional. When set, a token's {@code aud} claim must contain
 *       this value or the token is rejected.
 *   <li>{@code saiku.oauth.resource} — optional. The canonical resource identifier advertised in the
 *       protected-resource metadata ({@code resource} field). Defaults to the request's own URL.
 *   <li>{@code saiku.oauth.roleClaim} — which claim carries the caller's groups/roles. Supports a
 *       dotted path for nested claims (Keycloak's {@code realm_access.roles}). Defaults to {@code
 *       "roles"}.
 *   <li>{@code saiku.oauth.roleMapping.<claimValue>=<saikuRole>} — maps one claim value to a Saiku
 *       role name. Unmapped claim values are used verbatim (the issue's stated default behaviour).
 * </ul>
 */
public final class OAuthResourceServerProperties {

    public static final String PROP_ISSUER_URI = "saiku.oauth.issuerUri";
    public static final String PROP_AUDIENCE = "saiku.oauth.audience";
    public static final String PROP_RESOURCE = "saiku.oauth.resource";
    public static final String PROP_ROLE_CLAIM = "saiku.oauth.roleClaim";
    public static final String PROP_ROLE_MAPPING_PREFIX = "saiku.oauth.roleMapping.";
    public static final String DEFAULT_ROLE_CLAIM = "roles";

    private final String issuerUri;
    private final String audience;
    private final String resource;
    private final String roleClaim;
    private final Map<String, String> roleMapping;

    OAuthResourceServerProperties(
            String issuerUri, String audience, String resource, String roleClaim, Map<String, String> roleMapping) {
        this.issuerUri = blankToNull(issuerUri);
        this.audience = blankToNull(audience);
        this.resource = blankToNull(resource);
        this.roleClaim = (roleClaim == null || roleClaim.isBlank()) ? DEFAULT_ROLE_CLAIM : roleClaim.trim();
        this.roleMapping = roleMapping == null ? Collections.emptyMap() : Map.copyOf(roleMapping);
    }

    /** Reads the current process's system properties. Called once at bean-creation time; the
     *  launcher sets its {@code -D} flags before the webapp context loads. */
    public static OAuthResourceServerProperties fromSystemProperties() {
        return from(System.getProperties());
    }

    /** Builds from an arbitrary {@link Properties} instance rather than the live JVM properties —
     *  used by tests across the {@code org.saiku.web} tree (JAX-RS resource tests, filter tests) that
     *  need a properties object with no risk of leaking system-property state between test classes. */
    public static OAuthResourceServerProperties from(Properties props) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(PROP_ROLE_MAPPING_PREFIX) && name.length() > PROP_ROLE_MAPPING_PREFIX.length()) {
                String claimValue = name.substring(PROP_ROLE_MAPPING_PREFIX.length());
                mapping.put(claimValue, props.getProperty(name));
            }
        }
        return new OAuthResourceServerProperties(
                props.getProperty(PROP_ISSUER_URI),
                props.getProperty(PROP_AUDIENCE),
                props.getProperty(PROP_RESOURCE),
                props.getProperty(PROP_ROLE_CLAIM),
                mapping);
    }

    /** Whether the OAuth resource-server path is configured at all. False leaves every #878
     *  HTTP-Basic behaviour byte-for-byte unchanged. */
    public boolean isEnabled() {
        return issuerUri != null;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public String getAudience() {
        return audience;
    }

    public String getResource() {
        return resource;
    }

    public String getRoleClaim() {
        return roleClaim;
    }

    public Map<String, String> getRoleMapping() {
        return roleMapping;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
