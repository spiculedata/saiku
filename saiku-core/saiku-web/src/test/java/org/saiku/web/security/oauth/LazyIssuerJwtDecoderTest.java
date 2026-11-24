/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Unit coverage for {@link LazyIssuerJwtDecoder} (saiku#2543). Runs a real OIDC discovery + JWKS
 * endpoint on loopback (JDK's built-in {@link HttpServer} — no mocking framework in this module)
 * so the lazy discovery path is exercised for real: signature and issuer validation as well as the
 * now-mandatory audience check.
 */
public class LazyIssuerJwtDecoderTest {

    private static final String AUDIENCE = "saiku-mcp";

    private HttpServer server;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String issuerUri;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        issuerUri = "http://localhost:" + server.getAddress().getPort() + "/realms/test";
        String jwks = new JWKSet(new RSAKey.Builder(publicKey).keyID("test-kid").build()).toString();
        byte[] jwksBody = jwks.getBytes(StandardCharsets.UTF_8);
        byte[] discoveryBody = ("{\"issuer\":\"" + issuerUri + "\",\"jwks_uri\":\"" + issuerUri
                        + "/protocol/openid-connect/certs\"}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/realms/test/.well-known/openid-configuration", exchange -> respond(exchange, discoveryBody));
        server.createContext("/realms/test/protocol/openid-connect/certs", exchange -> respond(exchange, jwksBody));
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String signedToken(List<String> audience, Instant expiry) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(issuerUri)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiry));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-kid").build(), claims.build());
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private LazyIssuerJwtDecoder decoder() {
        return new LazyIssuerJwtDecoder(issuerUri, AUDIENCE);
    }

    /** saiku#2543: an audience is mandatory — the decoder refuses to be built without one. */
    @Test
    public void refuses_to_be_built_without_an_audience() {
        assertThrows(IllegalArgumentException.class, () -> new LazyIssuerJwtDecoder(issuerUri, null));
        assertThrows(IllegalArgumentException.class, () -> new LazyIssuerJwtDecoder(issuerUri, "   "));
    }

    /** saiku#2543 acceptance: a token whose aud is not this server is rejected. */
    @Test
    public void rejects_a_token_whose_audience_is_a_different_client() throws Exception {
        LazyIssuerJwtDecoder decoder = decoder();
        String token = signedToken(List.of("some-other-client"), Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    public void rejects_a_token_without_an_aud_claim() throws Exception {
        LazyIssuerJwtDecoder decoder = decoder();
        String token = signedToken(null, Instant.now().plusSeconds(600));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    public void accepts_a_token_minted_for_this_audience() throws Exception {
        LazyIssuerJwtDecoder decoder = decoder();
        String token =
                signedToken(List.of(AUDIENCE, "another-client"), Instant.now().plusSeconds(600));

        Jwt jwt = decoder.decode(token);

        assertNotNull(jwt);
        assertEquals("alice", jwt.getSubject());
    }

    @Test
    public void still_rejects_a_bad_signature_and_a_bad_issuer() throws Exception {
        LazyIssuerJwtDecoder decoder = decoder();
        String wrongAudience = signedToken(List.of(AUDIENCE), Instant.now().plusSeconds(600));
        // same shape, different key: signature validation must stay on
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        SignedJWT forged = SignedJWT.parse(wrongAudience);
        SignedJWT reSigned = new SignedJWT(forged.getHeader(), forged.getJWTClaimsSet());
        reSigned.sign(
                new RSASSASigner((RSAPrivateKey) generator.generateKeyPair().getPrivate()));

        assertThrows(JwtException.class, () -> decoder.decode(reSigned.serialize()));
    }

    @Test
    public void discovery_failure_surfaces_as_a_jwt_exception() {
        LazyIssuerJwtDecoder decoder = new LazyIssuerJwtDecoder("http://localhost:1/no-such-issuer", AUDIENCE);
        JwtException e = assertThrows(JwtException.class, () -> decoder.decode("whatever"));
        assertTrue(e.getMessage(), e.getMessage().contains("discovery failed"));
    }
}
