/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Spring XML factory-method bean: builds the {@link JwtDecoder} the bearer-auth filter uses, or
 *  {@code null} when OAuth isn't configured (see {@link OAuthResourceServerProperties#isEnabled()}). */
public final class OAuthJwtDecoderFactory {

    private OAuthJwtDecoderFactory() {}

    public static JwtDecoder create(OAuthResourceServerProperties properties) {
        return properties.isEnabled()
                ? new LazyIssuerJwtDecoder(properties.getIssuerUri(), properties.getAudience())
                : null;
    }
}
