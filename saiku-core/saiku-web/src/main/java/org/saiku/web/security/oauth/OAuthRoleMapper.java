/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a validated bearer token's claims onto Saiku {@link GrantedAuthority} names (saiku#879),
 * reusing the exact role-propagation hook #878 already wired: {@code SecurityAwareConnectionManager}
 * reads the {@link org.springframework.security.core.context.SecurityContextHolder}'s authority set
 * to resolve the caller's Mondrian role, whatever authentication mechanism populated it.
 *
 * <p>The configured {@link OAuthResourceServerProperties#getRoleClaim()} names the claim carrying the
 * caller's groups/roles — a plain claim ({@code "groups"}, {@code "roles"}) or a dotted path into a
 * nested object ({@code "realm_access.roles"}, Keycloak's default shape). Each claim value is passed
 * through {@link OAuthResourceServerProperties#getRoleMapping()}; an unmapped value is used verbatim
 * (the issue's stated default), then normalised to the {@code ROLE_}-prefixed Spring Security
 * convention the in-memory {@code users.properties} provider already uses.
 */
public final class OAuthRoleMapper {

    private final OAuthResourceServerProperties properties;

    public OAuthRoleMapper(OAuthResourceServerProperties properties) {
        this.properties = properties;
    }

    public List<GrantedAuthority> mapAuthorities(Jwt jwt) {
        List<String> claimValues = extractClaimValues(jwt.getClaims(), properties.getRoleClaim());
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String claimValue : claimValues) {
            if (claimValue == null || claimValue.isBlank()) {
                continue;
            }
            String mapped = properties.getRoleMapping().getOrDefault(claimValue, claimValue);
            authorities.add(new SimpleGrantedAuthority(toRoleAuthority(mapped)));
        }
        return authorities;
    }

    private static String toRoleAuthority(String roleName) {
        String trimmed = roleName.trim();
        return trimmed.startsWith("ROLE_") ? trimmed : "ROLE_" + trimmed;
    }

    /**
     * Resolves a dotted claim path (e.g. {@code "realm_access.roles"}) against the token's claim map
     * and returns its value(s) as strings. Accepts a JSON array claim (each element stringified), a
     * single string claim (one-element list), or a missing/wrong-shaped claim (empty list — never
     * throws, so a misconfigured claim path denies rather than crashes the request).
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractClaimValues(Map<String, Object> claims, String dottedPath) {
        Object current = claims;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map)) {
                return List.of();
            }
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) {
                return List.of();
            }
        }
        if (current instanceof List) {
            List<String> values = new ArrayList<>();
            for (Object element : (List<?>) current) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }
            return values;
        }
        if (current instanceof String) {
            return List.of((String) current);
        }
        return List.of();
    }
}
