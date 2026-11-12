/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security;

/**
 * Marker authority carried by every identity a token-authenticating filter mints (embed guest
 * and author, share guest, SCIM). Spring's {@code isFullyAuthenticated()} is true for any
 * non-anonymous {@code Authentication}, guests included, so the fallback {@code intercept-url}
 * rules in {@code applicationContext-saiku.xml} read {@code isFullyAuthenticated() and
 * !hasRole('TOKEN_PRINCIPAL')}: only a real user satisfies them, and a guest is confined to
 * the prefixes that name its own role (#2343).
 */
public final class TokenPrincipals {

    /** Matches {@code hasRole('TOKEN_PRINCIPAL')} in the URL rules. */
    public static final String TOKEN_PRINCIPAL_ROLE = "ROLE_TOKEN_PRINCIPAL";

    private TokenPrincipals() {}
}
