/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Map;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mints a Google OAuth 2.0 access token for the Drive connector (saiku#1987).
 *
 * <p>A seam, not a strategy: {@link ServiceAccountTokenSupplier} is the one production implementation
 * (JWT-bearer grant), and tests substitute a canned supplier so the Drive HTTP conversation is
 * hermetic and no network call is made from a unit test.
 */
public interface DriveAccessTokenSupplier {

    /**
     * A currently-valid access token, refreshing if needed. Implementations may cache; a token is a
     * short-lived credential and must never be logged.
     */
    String accessToken() throws ExportDeliveryException;

    /**
     * Production supplier: mints a token with the <b>JWT-bearer (service account)</b> grant against
     * {@code https://oauth2.googleapis.com/token}, signed locally with the service account's RSA
     * private key. No {@code google-api-client} dependency, no client library, no token cache on
     * disk.
     *
     * <p><b>Threat model (this is the choice the issue asks us to document).</b> A Google service
     * account key is a long-lived, non-expiring credential that grants exactly the Drive scopes the
     * admin asked for. Concretely:
     *
     * <ul>
     *   <li><b>Least privilege.</b> Only {@code https://www.googleapis.com/auth/drive.file} is
     *       requested. That scope can create files <i>and</i> read/delete only the files this
     *       service account created — it cannot enumerate or read the admin's existing Drive
     *       content. Export data therefore cannot be read back out by anyone who obtains the key,
     *       and a leaked export does not expose the operator's whole Drive.</li>
     *   <li><b>Blast radius is one folder.</b> Uploads are pinned to the configured
     *       {@code folderId}. The key cannot write anywhere else, so key compromise is contained to
     *       creating/overwriting files in that one folder.</li>
     *   <li><b>Rotation is a key delete.</b> Revoking is deleting the key in Google Cloud IAM — no
     *       Saiku-side session to invalidate, and no token we stored that outlives it.</li>
     *   <li><b>Trust boundary.</b> The key file is admin-supplied, lives outside {@code saiku-home}
     *       so it is never picked up by a {@code saiku-home} backup, and the path (not the key) is
     *       what Saiku stores in the destination config. Saiku reads the file at delivery time; it
     *       never copies the key material into its own config store.</li>
     *   <li><b>Accepted, documented risk.</b> An admin who sets this up grants a long-lived
     *       credential to the Saiku host. We accept that in exchange for <b>no interactive OAuth
     *       round-trip</b>: an installed-app/refresh-token flow would need a browser consent screen
     *       and would put a long-lived, user-revocable <i>user</i> token on the host instead, which
     *       is a strictly larger blast radius (it sees the operator's whole Drive) for the same
     *       job. The alternative is called out as future work in
     *       {@code docs/EXPORT-DESTINATIONS.md}.</li>
     * </ul>
     */
    final class ServiceAccountTokenSupplier implements DriveAccessTokenSupplier {

        private static final Logger log = LoggerFactory.getLogger(ServiceAccountTokenSupplier.class);

        /** Google rejects an assertion with a lifetime over 1 hour; 3600 s is the documented max. */
        private static final long ASSERTION_LIFETIME_SECONDS = 3600L;

        private final String tokenEndpoint;
        private final String scope;
        private final ServiceAccountKey key;
        private final HttpClient http;

        /** Cached token plus its absolute expiry; a null {@code expiresAt} means "never expires". */
        private volatile String cached;

        private volatile long cachedUntilMillis;

        public ServiceAccountTokenSupplier(ServiceAccountKey key) {
            this(
                    key,
                    "https://oauth2.googleapis.com/token",
                    "https://www.googleapis.com/auth/drive.file",
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build());
        }

        /** Visible for tests: endpoint/scope/http are all overridable so nothing leaves the JVM. */
        public ServiceAccountTokenSupplier(ServiceAccountKey key, String tokenEndpoint, String scope, HttpClient http) {
            if (key == null) {
                throw new IllegalArgumentException("serviceAccountKey is required");
            }
            this.key = key;
            this.tokenEndpoint = tokenEndpoint;
            this.scope = scope;
            this.http = http;
        }

        @Override
        public String accessToken() throws ExportDeliveryException {
            long now = System.currentTimeMillis();
            String token = cached;
            if (token != null && cachedUntilMillis > now) {
                return token;
            }
            // Refresh a little early so a token cannot expire mid-upload.
            String minted = mint();
            this.cached = minted;
            this.cachedUntilMillis = now + (ASSERTION_LIFETIME_SECONDS - 60L) * 1000L;
            return minted;
        }

        private String mint() throws ExportDeliveryException {
            String assertion = key.signedAssertion(tokenEndpoint, scope, ASSERTION_LIFETIME_SECONDS);
            String body =
                    "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + enc(assertion);
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ExportDeliveryException.unavailable("interrupted while requesting a Drive access token");
            } catch (Exception e) {
                // e.getMessage() on an HttpClient failure can include the URI (fine) but never the
                // assertion; still, we only surface the type to be safe.
                throw ExportDeliveryException.unavailable("could not reach the Google token endpoint ("
                        + e.getClass().getSimpleName() + ")");
            }
            if (res.statusCode() / 100 != 2) {
                // Google's error body carries the reason; it never echoes the assertion, but we do not
                // forward it wholesale — extract nothing, report the status only.
                throw ExportDeliveryException.unavailable("Google token endpoint returned HTTP " + res.statusCode());
            }
            String accessToken = TokenResponse.readAccessToken(res.body());
            if (accessToken == null || accessToken.isBlank()) {
                throw ExportDeliveryException.unavailable("Google token response carried no access_token");
            }
            log.debug("Minted a Drive access token for service account {} (scope {})", key.clientEmail(), scope);
            return accessToken;
        }

        private static String enc(String s) {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }

    /**
     * A service-account key: {@code client_email}, {@code private_key_id} (the token URI audience) and
     * the RSA {@code private_key} PEM.
     */
    public record ServiceAccountKey(String clientEmail, String privateKeyId, PrivateKey privateKey) {

        /** Parse a Google-issued service-account JSON key file. Never logs the key material. */
        public static ServiceAccountKey fromJson(String json) throws ExportDeliveryException {
            Map<String, String> fields;
            try {
                fields = TokenResponse.readJsonFields(json);
            } catch (Exception e) {
                throw ExportDeliveryException.misconfigured("service-account key is not valid JSON");
            }
            String email = fields.get("client_email");
            String pem = fields.get("private_key");
            String keyId = fields.get("private_key_id");
            if (isBlank(email) || isBlank(pem)) {
                throw ExportDeliveryException.misconfigured(
                        "service-account key needs 'client_email' and 'private_key'");
            }
            return new ServiceAccountKey(email, keyId, RsaKeys.parsePkcs8Pem(pem));
        }

        /** Read + parse the key file. A missing/unreadable file is a config error, not a crash. */
        public static ServiceAccountKey fromFile(java.nio.file.Path path) throws ExportDeliveryException {
            String json;
            try {
                json = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // The PATH is operator-facing and safe to name; the file's contents are not.
                throw ExportDeliveryException.misconfigured("cannot read the service-account key file at " + path);
            }
            return fromJson(json);
        }

        /**
         * Build the signed JWT assertion the JWT-bearer grant needs: header
         * {@code {"alg":"RS256","typ":"JWT"}}, claims
         * {@code {"iss":email,"scope":scope,"aud":tokenUri,"exp":…,"iat":…}} where {@code aud} is the
         * token endpoint. The signing key is the account's own key id, which is what binds the
         * assertion to the key Google will verify it with.
         */
        String signedAssertion(String audience, String scope, long lifetimeSeconds) throws ExportDeliveryException {
            long now = System.currentTimeMillis() / 1000L;
            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String claims = base64Url(("{\"iss\":" + jsonString(clientEmail) + ",\"scope\":" + jsonString(scope)
                            + ",\"aud\":" + jsonString(audience) + ",\"iat\":" + now + ",\"exp\":"
                            + (now + lifetimeSeconds) + "}")
                    .getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + claims;
            byte[] signature = RsaKeys.sign(privateKey, signingInput);
            return signingInput + "." + base64Url(signature);
        }

        static String base64Url(byte[] bytes) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        /** Minimal JSON string escaping — the only interpolated values are emails and fixed URLs. */
        static String jsonString(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }
}
