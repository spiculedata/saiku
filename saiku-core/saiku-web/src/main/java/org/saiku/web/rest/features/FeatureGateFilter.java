/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.features;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.saiku.service.features.FeatureCatalogue;
import org.saiku.service.features.FeatureToggles;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2618 — server-side enforcement of the admin feature toggles.
 *
 * <p>The admin panel can turn the main UI features (Dashboards, Apps, Notebooks,
 * the SQL workbench) off per role. Hiding the SPA's nav entry is not enforcement:
 * a user with a direct link would still call the backing REST surface. This
 * filter closes that — it runs inside the Jersey servlet, after the Spring
 * Security chain has authenticated the request, and refuses (403
 * {@code FEATURE_DISABLED}) any call to a feature's REST prefixes by a caller
 * whose authorities the admin has not granted, unless the caller is a
 * {@code ROLE_ADMIN} (the admin surface must always be reachable for an admin).
 *
 * <p>Restriction-only: the toggles can only narrow what the Spring Security
 * chain already granted, never widen it. An unrestricted feature, a missing
 * principal (Spring answers 401), or any error reading the toggles must fail
 * the request only through the gates that actually apply — this filter never
 * throws, and a broken registry degrades to "pass through and let Spring and
 * the resources decide".
 *
 * <p>The MDX workbench ({@code workbench}) has no REST prefix here: it shares
 * {@code /saiku/api/query} with the main workspace query editor, and gating the
 * shared path would deny the workspace too. That feature is enforced at the UI
 * layer only ({@link FeatureCatalogue#serverEnforced} is false), which the admin
 * panel states.
 */
@Priority(Priorities.AUTHORIZATION)
public class FeatureGateFilter implements ContainerRequestFilter {

    /** 403 body: same envelope posture as the other typed refusals — no internals leaked. */
    static final String ERROR_CODE = "FEATURE_DISABLED";

    /** Feature key -> longest REST prefix, built from the catalogue. Package-visible for tests. */
    static final Map<String, String> PREFIX_BY_KEY;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (FeatureCatalogue feature : FeatureCatalogue.values()) {
            for (String prefix : feature.restPrefixes) {
                String normalised = prefix.startsWith("/") ? prefix : "/" + prefix;
                m.put(normalised, feature.key);
            }
        }
        PREFIX_BY_KEY = Collections.unmodifiableMap(m);
    }

    private final FeatureToggles toggles;

    public FeatureGateFilter(FeatureToggles toggles) {
        this.toggles = toggles;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String key = featureOf(pathOf(requestContext));
        if (key == null) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        // No real principal: the Spring URL gate owns the 401/403 for anonymous
        // callers; we never fabricate one and never deny on their behalf here.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if (ga != null && FeatureToggles.ADMIN_AUTHORITY.equals(ga.getAuthority())) {
                return;
            }
        }
        if (toggles.isEnabled(key, authoritiesOf(authentication))) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "status", "ERROR",
                        "error", ERROR_CODE,
                        "feature", key,
                        "message", "This feature has been disabled for your account by an administrator."))
                .build());
    }

    /** The request path within the Jersey mount, with a leading slash. */
    private static String pathOf(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** The feature whose REST prefix matches the path (exact or longest-prefix), else null. */
    static String featureOf(String path) {
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> e : PREFIX_BY_KEY.entrySet()) {
            String prefix = e.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                if (prefix.length() > bestLen) {
                    best = e.getValue();
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    private static java.util.Set<String> authoritiesOf(Authentication authentication) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if (ga != null && ga.getAuthority() != null) {
                out.add(ga.getAuthority());
            }
        }
        return out;
    }
}
