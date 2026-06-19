/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.token;

import java.util.Optional;

/**
 * SPI for validating bearer tokens extracted from an {@code Authorization: Bearer <token>}
 * header.
 *
 * <p>Implementations return a populated {@link AuthedPrincipal} on success, or
 * {@link Optional#empty()} when the token is absent, invalid, expired, or revoked.
 * The {@link BearerTokenAuthFilter} calls this and, on a non-empty result, sets
 * the authenticated principal in the Spring Security context for the duration of
 * the request.
 *
 * <p>The default wired implementation is {@link RejectAllTokenValidator}; swap it
 * with an OIDC or JWT verifier by replacing the {@code tokenValidator} bean in
 * {@code saiku-beans.xml}.
 */
@FunctionalInterface
public interface TokenValidator {

    /**
     * Validate {@code token} and return the authenticated principal on success.
     *
     * @param token the raw bearer token string (everything after {@code "Bearer "})
     * @return the principal, or empty if the token is invalid
     */
    Optional<AuthedPrincipal> validate(String token);
}
