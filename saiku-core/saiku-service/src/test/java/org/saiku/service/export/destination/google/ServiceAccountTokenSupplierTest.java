/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * The service-account JWT-bearer grant (saiku#1987), against a loopback token endpoint. Covers the
 * RS256 signing path, the audience binding, caching, and the sanitised error mapping.
 */
public class ServiceAccountTokenSupplierTest {

    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";

    private HttpServer server;
    private String tokenEndpoint;
    private int tokenCalls;
    private String lastForm;
    private int respondStatus = 200;
    private String respondBody = "{\"access_token\":\"ya29.canned\",\"expires_in\":3600}";

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/token", this::handleToken);
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenCalls++;
        lastForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = respondBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(respondStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static DriveAccessTokenSupplier.ServiceAccountKey key() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return new DriveAccessTokenSupplier.ServiceAccountKey(
                "saiku@example.iam.gserviceaccount.com", "key-id-1", (PrivateKey)
                        kpg.generateKeyPair().getPrivate());
    }

    private DriveAccessTokenSupplier.ServiceAccountTokenSupplier supplier(
            DriveAccessTokenSupplier.ServiceAccountKey key) {
        return new DriveAccessTokenSupplier.ServiceAccountTokenSupplier(
                key, tokenEndpoint, SCOPE, HttpClient.newHttpClient());
    }

    // ---------------- the happy path ----------------

    @Test
    public void mintsATokenThroughTheJwtBearerGrant() throws Exception {
        assertEquals("ya29.canned", supplier(key()).accessToken());
        assertTrue(lastForm, lastForm.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"));
        assertTrue("the assertion must be posted as a form field", lastForm.contains("assertion="));
    }

    @Test
    public void theAssertionIsAJwtWithTheRightHeaderAndClaims() throws Exception {
        supplier(key()).accessToken();
        String assertion = formField(lastForm, "assertion");
        String[] parts = assertion.split("\\.");
        assertEquals("header.payload.signature", 3, parts.length);

        Map<String, String> header = decodeSegment(parts[0]);
        assertEquals("RS256", header.get("alg"));
        assertEquals("JWT", header.get("typ"));

        Map<String, String> claims = decodeSegment(parts[1]);
        assertEquals("saiku@example.iam.gserviceaccount.com", claims.get("iss"));
        assertEquals(tokenEndpoint, claims.get("aud"));
        assertEquals(SCOPE, claims.get("scope"));
        assertTrue(Long.parseLong(claims.get("exp")) > Long.parseLong(claims.get("iat")));
        assertTrue(
                "the assertion lifetime must stay inside Google's 1-hour limit",
                Long.parseLong(claims.get("exp")) - Long.parseLong(claims.get("iat")) <= 3600L);
    }

    /**
     * The {@code aud} claim must be the token endpoint we actually POST to. Getting this wrong is the
     * classic JWT-bearer bug: Google rejects the assertion, and the operator sees an opaque 400.
     */
    @Test
    public void theAudienceIsTheTokenEndpointWePostTo() throws Exception {
        supplier(key()).accessToken();
        assertEquals(
                tokenEndpoint,
                decodeSegment(formField(lastForm, "assertion").split("\\.")[1]).get("aud"));
    }

    @Test
    public void theSignatureVerifiesAgainstTheKeyWeLoaded() throws Exception {
        DriveAccessTokenSupplier.ServiceAccountKey k = key();
        String assertion = k.signedAssertion("https://oauth2.googleapis.com/token", SCOPE, 3600);
        String[] parts = assertion.split("\\.");
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        // Re-derive the public key from the private one and verify — a broken signing path fails here.
        var kf = java.security.KeyFactory.getInstance("RSA");
        var pub = kf.generatePublic(new java.security.spec.RSAPublicKeySpec(
                ((java.security.interfaces.RSAPrivateCrtKey) k.privateKey()).getModulus(),
                ((java.security.interfaces.RSAPrivateCrtKey) k.privateKey()).getPublicExponent()));
        var verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pub);
        verifier.update(signed);
        assertTrue("the RS256 signature does not verify", verifier.verify(signature));
    }

    @Test
    public void cachesTheTokenRatherThanMintingPerCall() throws Exception {
        DriveAccessTokenSupplier.ServiceAccountTokenSupplier s = supplier(key());
        s.accessToken();
        s.accessToken();
        s.accessToken();
        assertEquals("the token should have been minted once and reused", 1, tokenCalls);
    }

    // ---------------- key parsing ----------------

    @Test
    public void parsesARealServiceAccountKeyFile() throws Exception {
        var k = DriveAccessTokenSupplier.ServiceAccountKey.fromJson(
                GoogleDriveExportDestinationTest.serviceAccountJson());
        assertEquals("saiku@example.iam.gserviceaccount.com", k.clientEmail());
        assertEquals("abc123", k.privateKeyId());
        assertNotNull(k.privateKey());
    }

    @Test
    public void rejectsAKeyFileThatIsNotJson() {
        try {
            DriveAccessTokenSupplier.ServiceAccountKey.fromJson("{ nope");
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not valid JSON"));
        }
    }

    @Test
    public void rejectsAKeyFileWithoutTheRequiredFields() {
        try {
            DriveAccessTokenSupplier.ServiceAccountKey.fromJson("{\"client_email\":\"a@b\"}");
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("private_key"));
        }
    }

    @Test
    public void rejectsAMissingKeyFileAndNamesOnlyThePath() {
        try {
            DriveAccessTokenSupplier.ServiceAccountKey.fromFile(java.nio.file.Path.of("/no/such/key.json"));
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("/no/such/key.json"));
        }
    }

    // ---------------- error mapping ----------------

    @Test
    public void mapsATokenRejectionToASanitizedError() throws Exception {
        respondStatus = 400;
        respondBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT signature\"}";
        try {
            supplier(key()).accessToken();
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("400"));
            assertFalse(
                    "the response body leaked: " + e.getMessage(),
                    e.getMessage().contains("invalid_grant"));
        }
    }

    @Test
    public void aTokenResponseWithoutAnAccessTokenIsAFailure() throws Exception {
        respondBody = "{\"token_type\":\"Bearer\"}";
        try {
            supplier(key()).accessToken();
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no access_token"));
        }
    }

    @Test
    public void anUnreachableTokenEndpointIsReportedWithoutTheAssertion() throws Exception {
        DriveAccessTokenSupplier.ServiceAccountTokenSupplier s =
                new DriveAccessTokenSupplier.ServiceAccountTokenSupplier(
                        key(), "http://127.0.0.1:1/token", SCOPE, HttpClient.newHttpClient());
        try {
            s.accessToken();
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertFalse(e.getMessage(), e.getMessage().contains("assertion="));
            assertTrue(e.getMessage(), e.getMessage().contains("could not reach"));
        }
    }

    // ---------------- JSON hardening ----------------

    @Test
    public void refusesToFlattenANestedValueIntoAConfigField() {
        try {
            var fields = TokenResponse.readJsonFields("{\"private_key\":{\"nested\":\"value\"}}");
            assertTrue("a nested object must not be readable as a string field", fields.isEmpty());
        } catch (ExportDeliveryException e) {
            fail("a flat-object projection should tolerate skipping, not throw: " + e.getMessage());
        }
    }

    @Test
    public void refusesATopLevelArrayOrScalar() {
        for (String bad : new String[] {"[1,2]", "\"a string\"", "42"}) {
            try {
                TokenResponse.readJsonFields(bad);
                fail("expected a rejection of '" + bad + "'");
            } catch (ExportDeliveryException expected) {
                // correct
            }
        }
    }

    @Test
    public void refusesAnEmptyDocument() {
        try {
            TokenResponse.readJsonFields("  ");
            fail("expected a rejection of the empty document");
        } catch (ExportDeliveryException expected) {
            // correct
        }
    }

    // ---------------- helpers ----------------

    private static String formField(String form, String key) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no '" + key + "' field in: " + form);
    }

    private static Map<String, String> decodeSegment(String segment) {
        byte[] json = Base64.getUrlDecoder().decode(segment);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (IOException e) {
            throw new AssertionError("segment is not JSON: " + new String(json, StandardCharsets.UTF_8), e);
        }
    }
}
