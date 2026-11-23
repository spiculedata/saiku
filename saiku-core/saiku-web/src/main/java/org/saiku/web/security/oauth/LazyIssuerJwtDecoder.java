/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * A {@link JwtDecoder} that defers OIDC discovery (fetching {@code
 * <issuer>/.well-known/openid-configuration} and the IdP's JWKS) until the first bearer token
 * actually needs validating, rather than at Spring context start-up.
 *
 * <p>The eager {@code NimbusJwtDecoder.withIssuerLocation(...).build()} call performs that discovery
 * synchronously. Doing it at bean-creation time would make the whole webapp fail to boot whenever the
 * configured IdP is briefly unreachable — for a feature that many deployments never even opt into.
 * Deferring it means: (1) start-up never depends on the IdP, and (2) discovery is retried on the next
 * bearer request if it failed, rather than wedged for the process's lifetime after one boot-time
 * hiccup.
 */
final class LazyIssuerJwtDecoder implements JwtDecoder {

    private final String issuerUri;
    private final String audience;
    private volatile NimbusJwtDecoder delegate;

    LazyIssuerJwtDecoder(String issuerUri, String audience) {
        this.issuerUri = issuerUri;
        this.audience = audience;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        NimbusJwtDecoder decoder = delegate;
        if (decoder == null) {
            synchronized (this) {
                decoder = delegate;
                if (decoder == null) {
                    decoder = buildDelegate();
                    delegate = decoder;
                }
            }
        }
        return decoder.decode(token);
    }

    private NimbusJwtDecoder buildDelegate() {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        } catch (RuntimeException e) {
            // OIDC discovery failed (IdP unreachable / bad issuer). Surface as a token-validation
            // failure (-> 401) rather than letting the exception escape and crash the caller — the
            // next bearer request retries discovery from scratch.
            throw new JwtException("OAuth issuer discovery failed for " + issuerUri, e);
        }
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        if (audience == null) {
            decoder.setJwtValidator(withIssuer);
        } else {
            OAuth2TokenValidator<Jwt> withAudience =
                    new JwtClaimValidator<java.util.List<String>>("aud", aud -> aud != null && aud.contains(audience));
            decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    withIssuer, withAudience));
        }
        return decoder;
    }
}
