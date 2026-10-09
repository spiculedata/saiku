/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit coverage for {@link OAuthBearerAuthenticationFilter}. Uses a real RSA-signed JWT + a real
 * {@link NimbusJwtDecoder} (no mocking framework in this module — matches {@code
 * EmbedAuthFilterTest}'s style) so signature and expiry validation are exercised for real; only OIDC
 * issuer discovery (network-dependent, covered by {@link LazyIssuerJwtDecoder}) is out of scope here.
 */
public class OAuthBearerAuthenticationFilterTest {

    private static final String MCP_PATH = "/rest/saiku/api/mcp";

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String signedToken(String subject, List<String> roles, Instant expiry) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiry));
        if (roles != null) {
            claims.claim("roles", roles);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private OAuthBearerAuthenticationFilter enabledFilter() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        OAuthResourceServerProperties properties = OAuthResourceServerProperties.from(raw);
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        return new OAuthBearerAuthenticationFilter(properties, decoder, new OAuthRoleMapper(properties));
    }

    private OAuthBearerAuthenticationFilter disabledFilter() {
        OAuthResourceServerProperties properties = OAuthResourceServerProperties.from(new Properties());
        return new OAuthBearerAuthenticationFilter(properties, null, new OAuthRoleMapper(properties));
    }

    @Test
    public void non_mcp_path_passes_through_even_with_bearer_header() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rest/saiku/api/ai/cubes");
        req.addHeader("Authorization", "Bearer whatever");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void mcp_path_without_authorization_header_falls_through_to_basic() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertTrue("no bearer token presented — must fall through unchanged", chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void mcp_path_with_basic_header_falls_through_untouched() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        req.addHeader("Authorization", "Basic YWRtaW46YWRtaW4=");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void bearer_token_rejected_when_oauth_not_configured() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        req.addHeader(
                "Authorization",
                "Bearer "
                        + signedToken(
                                "alice", List.of("ROLE_USER"), Instant.now().plusSeconds(600)));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        disabledFilter().doFilter(req, resp, chain);

        assertFalse("must not fall through to Basic with a Bearer header this deployment can't validate", chain.called);
        assertEquals(401, resp.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", resp.getHeader("WWW-Authenticate"));
    }

    @Test
    public void malformed_bearer_token_rejected_with_challenge() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        req.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertFalse(chain.called);
        assertEquals(401, resp.getStatus());
        assertEquals("Bearer error=\"invalid_token\"", resp.getHeader("WWW-Authenticate"));
    }

    @Test
    public void expired_bearer_token_rejected_with_challenge() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        req.addHeader(
                "Authorization",
                "Bearer "
                        + signedToken(
                                "alice", List.of("ROLE_USER"), Instant.now().minusSeconds(60)));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertFalse(chain.called);
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void valid_bearer_token_authenticates_the_request() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", MCP_PATH);
        req.addHeader(
                "Authorization",
                "Bearer "
                        + signedToken(
                                "analyst-usa",
                                List.of("saiku-admin"),
                                Instant.now().plusSeconds(600)));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();

        enabledFilter().doFilter(req, resp, chain);

        assertTrue(chain.called);
        assertTrue(chain.capturedAuth instanceof JwtAuthenticationToken);
        assertEquals("analyst-usa", chain.capturedAuth.getName());
        assertTrue(chain.capturedAuth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_saiku-admin")));
        assertNull(
                "context must not leak past the request",
                SecurityContextHolder.getContext().getAuthentication());
    }

    private static class TrackingChain extends MockFilterChain {
        boolean called = false;

        @Override
        public void doFilter(ServletRequest req, ServletResponse resp) {
            called = true;
        }
    }

    private static class ContextCapturingChain extends MockFilterChain {
        boolean called = false;
        Authentication capturedAuth;

        @Override
        public void doFilter(ServletRequest req, ServletResponse resp) {
            called = true;
            capturedAuth = SecurityContextHolder.getContext().getAuthentication();
        }
    }
}
