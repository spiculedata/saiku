/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.trust.SuppressionStore;
import org.saiku.service.mail.trust.UnsubscribeTokens;

/**
 * saiku#1811 PR2 — the PUBLIC one-click unsubscribe endpoint. Locks the security contract: generic
 * 200 for valid AND invalid tokens (no enumeration oracle), a valid token actually suppresses, an
 * invalid token does NOT, idempotency, and that the address is never echoed in the response body.
 */
public class UnsubscribeResourceTest {

    private static final byte[] KEY = "test-install-key-material-32-byteXX".getBytes(StandardCharsets.UTF_8);

    private Path home;
    private SuppressionStore store;
    private UnsubscribeTokens tokens;

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailsuppress-it-");
        store = new SuppressionStore(home);
        tokens = new UnsubscribeTokens(KEY);
    }

    @After
    public void tearDown() throws Exception {
        // Best-effort temp cleanup.
        try {
            Files.deleteIfExists(home.resolve("mail-suppression.json"));
            Files.deleteIfExists(home);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private UnsubscribeResource resource() {
        UnsubscribeResource r = new UnsubscribeResource();
        r.setSuppressionStore(store);
        r.setUnsubscribeTokens(tokens);
        return r;
    }

    @Test
    public void validToken_returns200_andSuppresses() {
        String token = tokens.tokenFor("alice@example.com");
        Response resp = resource().unsubscribePost("alice@example.com", token);
        assertEquals(200, resp.getStatus());
        assertTrue(store.isSuppressed("alice@example.com"));
        // The address is never echoed in the body (no oracle / no PII leak).
        assertFalse(resp.getEntity().toString().contains("alice@example.com"));
    }

    @Test
    public void invalidToken_returns200_butDoesNotSuppress() {
        Response resp = resource().unsubscribePost("alice@example.com", "not-a-valid-token");
        // GENERIC 200 — identical to the valid case, so a caller can't distinguish outcomes.
        assertEquals(200, resp.getStatus());
        assertFalse("an invalid token must never suppress", store.isSuppressed("alice@example.com"));
    }

    @Test
    public void forgedTokenForThirdParty_returns200_butDoesNotSuppressThatParty() {
        // Attacker holds alice's token, tries to unsubscribe bob.
        String aliceToken = tokens.tokenFor("alice@example.com");
        Response resp = resource().unsubscribePost("bob@example.com", aliceToken);
        assertEquals(200, resp.getStatus());
        assertFalse(store.isSuppressed("bob@example.com"));
    }

    @Test
    public void validAndInvalid_returnByteIdenticalBody_noOracle() {
        String token = tokens.tokenFor("alice@example.com");
        Response valid = resource().unsubscribePost("alice@example.com", token);
        Response invalid = resource().unsubscribePost("carol@example.com", "bogus");
        assertEquals(valid.getStatus(), invalid.getStatus());
        assertEquals(valid.getEntity(), invalid.getEntity());
    }

    @Test
    public void getVariant_alsoWorks() {
        String token = tokens.tokenFor("alice@example.com");
        Response resp = resource().unsubscribeGet("alice@example.com", token);
        assertEquals(200, resp.getStatus());
        assertTrue(store.isSuppressed("alice@example.com"));
    }

    @Test
    public void idempotent_secondUnsubscribeStill200() {
        String token = tokens.tokenFor("alice@example.com");
        assertEquals(200, resource().unsubscribePost("alice@example.com", token).getStatus());
        assertEquals(200, resource().unsubscribePost("alice@example.com", token).getStatus());
        assertTrue(store.isSuppressed("alice@example.com"));
        assertEquals(1, store.list().count());
    }

    @Test
    public void rateLimited_returns429_whenBudgetExhausted() {
        UnsubscribeResource r = resource();
        // A 1-per-window limiter: first call passes, second trips.
        r.setRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1, 60_000L));
        String token = tokens.tokenFor("alice@example.com");
        assertEquals(200, r.unsubscribePost("alice@example.com", token).getStatus());
        assertEquals(429, r.unsubscribePost("alice@example.com", token).getStatus());
    }

    @Test
    public void malformedAddress_withAnyToken_returns200_noSuppress() {
        Response resp = resource().unsubscribePost("not-an-email", "whatever");
        assertEquals(200, resp.getStatus());
        assertFalse(store.isSuppressed("not-an-email"));
    }
}
