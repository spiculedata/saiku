/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.saiku.service.mail.trust.RecipientGate.Decision;

/**
 * saiku#1811 PR3 — the SINGLE fail-closed chokepoint. Exhaustively locks every deny branch: suppressed
 * (even when allowlisted + confirmed) → DENY; not allowlisted → DENY; allowlisted but PENDING / REVOKED
 * / absent consent → DENY; only (not suppressed ∧ allowlisted ∧ CONFIRMED) → ALLOW. Also: clear()
 * filters a mixed set, empty stores allow nobody, and a null store denies.
 */
class RecipientGateTest {

    /** Build a fully-trusted address: allowlisted AND consent CONFIRMED, not suppressed. */
    private String confirmAndAllow(
            RecipientTrustStore trust, RecipientConsentStore consent, String address, boolean allowlist) {
        if (allowlist) {
            trust.save(List.of(address), List.of());
        }
        String token = consent.requestConsent(address);
        consent.confirm(address, token);
        return address;
    }

    private RecipientGate gate(Path home) {
        return new RecipientGate(
                new SuppressionStore(home), new RecipientTrustStore(home), new RecipientConsentStore(home));
    }

    @Test
    void allTrusted_allows(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        confirmAndAllow(trust, consent, "alice@example.com", true);

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.ALLOW, g.decide("alice@example.com"));
        assertTrue(g.isAllowed("alice@example.com"));
    }

    @Test
    void suppressed_deniesEvenWhenAllowlistedAndConfirmed(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        confirmAndAllow(trust, consent, "alice@example.com", true);
        // Suppression is the top veto — it outranks allowlist AND consent.
        supp.suppress("alice@example.com", "unsubscribe");

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void notAllowlisted_denies(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        // Confirm consent but do NOT allowlist.
        confirmAndAllow(trust, consent, "alice@example.com", false);

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void allowlistedButConsentPending_denies(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        trust.save(List.of("alice@example.com"), List.of());
        consent.requestConsent("alice@example.com"); // PENDING, never confirmed

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void allowlistedButConsentRevoked_denies(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        trust.save(List.of("alice@example.com"), List.of());
        String token = consent.requestConsent("alice@example.com");
        consent.confirm("alice@example.com", token);
        consent.revoke("alice@example.com");

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void allowlistedButConsentAbsent_denies(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        trust.save(List.of("alice@example.com"), List.of());
        // No consent record at all.

        RecipientGate g = new RecipientGate(supp, trust, consent);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void malformedOrBlankAddress_denies(@TempDir Path home) {
        RecipientGate g = gate(home);
        assertEquals(Decision.DENY, g.decide(null));
        assertEquals(Decision.DENY, g.decide(""));
        assertEquals(Decision.DENY, g.decide("   "));
        assertEquals(Decision.DENY, g.decide("not-an-email"));
        assertEquals(Decision.DENY, g.decide("inj@example.com\r\nBcc: evil@x.com"));
    }

    @Test
    void emptyStores_allowNobody(@TempDir Path home) {
        RecipientGate g = gate(home);
        assertEquals(Decision.DENY, g.decide("alice@example.com"));
    }

    @Test
    void nullStores_denyFailClosed(@TempDir Path home) {
        assertEquals(Decision.DENY, new RecipientGate(null, null, null).decide("alice@example.com"));
    }

    @Test
    void clear_filtersMixedSetToSurvivorsOnly(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);

        // alice: fully trusted -> survives.
        trust.save(List.of("alice@example.com", "bob@example.com", "dave@example.com"), List.of());
        String at = consent.requestConsent("alice@example.com");
        consent.confirm("alice@example.com", at);
        // bob: allowlisted but consent only PENDING -> dropped.
        consent.requestConsent("bob@example.com");
        // dave: allowlisted + confirmed but then SUPPRESSED -> dropped.
        String dt = consent.requestConsent("dave@example.com");
        consent.confirm("dave@example.com", dt);
        supp.suppress("dave@example.com", "unsubscribe");
        // carol: confirmed but NOT allowlisted -> dropped.
        String ct = consent.requestConsent("carol@example.com");
        consent.confirm("carol@example.com", ct);

        RecipientGate g = new RecipientGate(supp, trust, consent);
        List<String> survivors = g.clear(List.of(
                "alice@example.com", "bob@example.com", "dave@example.com", "carol@example.com", "mallory@x.com"));
        assertEquals(List.of("alice@example.com"), survivors);
    }

    @Test
    void clear_nullOrEmpty_yieldsEmpty(@TempDir Path home) {
        RecipientGate g = gate(home);
        assertTrue(g.clear(null).isEmpty());
        assertTrue(g.clear(List.of()).isEmpty());
    }

    @Test
    void clear_deduplicatesNormalisedVariants(@TempDir Path home) {
        SuppressionStore supp = new SuppressionStore(home);
        RecipientTrustStore trust = new RecipientTrustStore(home);
        RecipientConsentStore consent = new RecipientConsentStore(home);
        trust.save(List.of("alice@example.com"), List.of());
        String at = consent.requestConsent("alice@example.com");
        consent.confirm("alice@example.com", at);

        RecipientGate g = new RecipientGate(supp, trust, consent);
        List<String> survivors = g.clear(List.of("alice@example.com", "ALICE@Example.com", "  alice@example.com  "));
        assertEquals(List.of("alice@example.com"), survivors);
    }
}
