/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.token;

import java.util.Optional;

/**
 * Default {@link TokenValidator} that unconditionally rejects every bearer token.
 *
 * <p>Wired as the {@code tokenValidator} bean in {@code saiku-beans.xml} so the
 * {@link BearerTokenAuthFilter} is present in the chain from day one without
 * granting any access. Replace this bean with a real OIDC / JWT verifier to
 * enable bearer-token authentication.
 */
public class RejectAllTokenValidator implements TokenValidator {

    @Override
    public Optional<AuthedPrincipal> validate(String token) {
        return Optional.empty();
    }
}
