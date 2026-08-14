/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.trust.RecipientConsentStore;

/**
 * saiku#1811 PR3 — the PUBLIC double-opt-in consent-confirm endpoint. Locks the security contract:
 * generic 200 for valid AND invalid tokens (no enumeration oracle), a valid token actually confirms,
 * an invalid / expired token does NOT, idempotency, rate-limit 429, and that the address is never
 * echoed in the response body.
 */
public class ConsentConfirmResourceTest {

    private Path home;
    private RecipientConsentStore store;

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailconsent-it-");
        store = new RecipientConsentStore(home);
    }

    @After
    public void tearDown() throws Exception {
        try {
            Files.deleteIfExists(home.resolve("mail-consent.json"));
            Files.deleteIfExists(home);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private ConsentConfirmResource resource() {
        ConsentConfirmResource r = new ConsentConfirmResource();
        r.setConsentStore(store);
        return r;
    }

    @Test
    public void validToken_returns200_andConfirms() {
        String token = store.requestConsent("alice@example.com");
        Response resp = resource().confirm(token, "alice@example.com");
        assertEquals(200, resp.getStatus());
        assertTrue(store.isConfirmed("alice@example.com"));
        // The address is never echoed in the body (no oracle / no PII leak).
        assertFalse(resp.getEntity().toString().contains("alice@example.com"));
    }

    @Test
    public void invalidToken_returns200_butDoesNotConfirm() {
        store.requestConsent("alice@example.com");
        Response resp = resource().confirm("not-a-valid-token", "alice@example.com");
        // GENERIC 200 — identical to the valid case, so a caller can't distinguish outcomes.
        assertEquals(200, resp.getStatus());
        assertFalse("an invalid token must never confirm", store.isConfirmed("alice@example.com"));
    }

    @Test
    public void expiredToken_returns200_butDoesNotConfirm() throws Exception {
        // Negative TTL => tokenExpiresAt is unambiguously in the past (deterministic; avoids the
        // same-millisecond flake a zero TTL risks).
        RecipientConsentStore expiring = new RecipientConsentStore(home, -1000L);
        String token = expiring.requestConsent("alice@example.com");
        ConsentConfirmResource r = new ConsentConfirmResource();
        r.setConsentStore(expiring);
        Response resp = r.confirm(token, "alice@example.com");
        assertEquals(200, resp.getStatus());
        assertFalse(expiring.isConfirmed("alice@example.com"));
    }

    @Test
    public void unknownAddress_returns200_noConfirm() {
        Response resp = resource().confirm("whatever", "nobody@example.com");
        assertEquals(200, resp.getStatus());
        assertFalse(store.isConfirmed("nobody@example.com"));
    }

    @Test
    public void validAndInvalid_returnByteIdenticalBody_noOracle() {
        String token = store.requestConsent("alice@example.com");
        Response valid = resource().confirm(token, "alice@example.com");
        Response invalid = resource().confirm("bogus", "carol@example.com");
        assertEquals(valid.getStatus(), invalid.getStatus());
        assertEquals(valid.getEntity(), invalid.getEntity());
    }

    @Test
    public void idempotent_secondConfirmStill200() {
        String token = store.requestConsent("alice@example.com");
        assertEquals(200, resource().confirm(token, "alice@example.com").getStatus());
        assertEquals(200, resource().confirm(token, "alice@example.com").getStatus());
        assertTrue(store.isConfirmed("alice@example.com"));
        assertEquals(1, store.list().count());
    }

    @Test
    public void rateLimited_returns429_whenBudgetExhausted() {
        ConsentConfirmResource r = resource();
        // A 1-per-window limiter: first call passes, second trips.
        r.setRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1, 60_000L));
        String token = store.requestConsent("alice@example.com");
        assertEquals(200, r.confirm(token, "alice@example.com").getStatus());
        assertEquals(429, r.confirm(token, "alice@example.com").getStatus());
    }

    @Test
    public void malformedAddress_withAnyToken_returns200_noConfirm() {
        Response resp = resource().confirm("whatever", "not-an-email");
        assertEquals(200, resp.getStatus());
        assertFalse(store.isConfirmed("not-an-email"));
    }

    // ---- saiku#1811 PR4, SEC carry-forward #2: per-target-address confirm cap ----

    @Test
    public void perAddressCap_returns429_afterBudgetExhausted_forSameAddress() {
        ConsentConfirmResource r = resource();
        // Generous per-IP limiter so it isn't the one that trips; a 2-per-window per-ADDRESS cap.
        r.setRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1000, 60_000L));
        r.setAddressRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(2, 60_000L));
        assertEquals(200, r.confirm("bogus1", "victim@example.com").getStatus());
        assertEquals(200, r.confirm("bogus2", "victim@example.com").getStatus());
        // Third attempt against the SAME address is capped even though the per-IP budget is fine.
        assertEquals(429, r.confirm("bogus3", "victim@example.com").getStatus());
    }

    @Test
    public void perAddressCap_isolatesDifferentAddresses() {
        ConsentConfirmResource r = resource();
        r.setRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1000, 60_000L));
        r.setAddressRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1, 60_000L));
        // Exhaust the cap for address A.
        assertEquals(200, r.confirm("x", "a@example.com").getStatus());
        assertEquals(429, r.confirm("x", "a@example.com").getStatus());
        // A different address B still has its own budget — the cap is per-address, not global.
        assertEquals(200, r.confirm("x", "b@example.com").getStatus());
    }

    @Test
    public void perAddressCap_429BodyIsGeneric_noAddressEchoed() {
        ConsentConfirmResource r = resource();
        r.setRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1000, 60_000L));
        r.setAddressRateLimiter(new org.saiku.web.security.ratelimit.AiRateLimiter(1, 60_000L));
        r.confirm("x", "secret@example.com");
        Response capped = r.confirm("x", "secret@example.com");
        assertEquals(429, capped.getStatus());
        assertFalse(capped.getEntity().toString().contains("secret@example.com"));
    }
}
