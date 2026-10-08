/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * RSA key handling for the Drive service-account JWT-bearer grant (saiku#1987). JDK-only — no
 * Bouncy Castle, no {@code google-api-client}.
 *
 * <p>Isolated in its own class so the signing/parsing code stays auditable: this is the one place in
 * the connector that touches key material, and it never puts any of it into an exception message.
 */
final class RsaKeys {

    private RsaKeys() {}

    /**
     * Parse a PKCS#8 {@code -----BEGIN PRIVATE KEY-----} PEM. Google issues PKCS#8; we reject
     * PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) explicitly rather than guessing, so an operator who
     * pastes the wrong block gets a clear message instead of a JCE stack trace.
     */
    static PrivateKey parsePkcs8Pem(String pem) throws ExportDeliveryException {
        String body = pem == null
                ? ""
                : pem.replaceAll("-----BEGIN (.*)-----", "")
                        .replaceAll("-----END (.*)-----", "")
                        .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw ExportDeliveryException.misconfigured("service-account private_key is not valid base64");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw ExportDeliveryException.misconfigured("service-account private_key is not a PKCS#8 RSA key ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** RS256-sign {@code signingInput} (the {@code header.claims} form) with {@code key}. */
    static byte[] sign(PrivateKey key, String signingInput) throws ExportDeliveryException {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw ExportDeliveryException.misconfigured("could not sign the Google token assertion");
        }
    }
}
