/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1811 PR3 — the writable, file-backed double-opt-in consent store. Locks: request→PENDING,
 * confirm-with-correct-token→CONFIRMED, wrong/expired/tampered token→no flip, hash-not-reversible
 * (stored value != raw token), revoke, idempotency, normalisation, fail-closed isConfirmed, atomic
 * write, in-memory fallback and corrupt-file resilience.
 *
 * <p>The store is DORMANT (no send path consults it yet) so these tests exercise it directly, minting
 * tokens as the store itself does.
 */
class RecipientConsentStoreTest {

    private RecipientConsentStore store(Path home) {
        return new RecipientConsentStore(home);
    }

    @Test
    void missingFile_readsAsEmpty_andConfirmsNobody(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        assertFalse(s.exists());
        assertTrue(s.read().isEmpty());
        assertFalse(s.isConfirmed("anyone@acme.com"));
        ConsentView v = s.list();
        assertTrue(v.entries().isEmpty());
        assertEquals(0, v.count());
    }

    @Test
    void requestConsent_recordsPending_andReturnsRawToken(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertTrue(s.exists());
        // PENDING, not yet confirmed.
        assertFalse(s.isConfirmed("alice@example.com"));
        ConsentView.Entry e = s.list().entries().get(0);
        assertEquals(ConsentStatus.PENDING, e.status());
        assertTrue(e.requestedAt() > 0);
        assertTrue(e.tokenExpiresAt() > e.requestedAt());
    }

    @Test
    void confirm_withCorrectToken_flipsToConfirmed(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        assertTrue(s.confirm("alice@example.com", token));
        assertTrue(s.isConfirmed("alice@example.com"));
        assertEquals(ConsentStatus.CONFIRMED, s.list().entries().get(0).status());
    }

    @Test
    void confirm_wrongToken_doesNotFlip(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        s.requestConsent("alice@example.com");
        assertFalse(s.confirm("alice@example.com", "not-the-token"));
        assertFalse(s.isConfirmed("alice@example.com"));
        assertEquals(ConsentStatus.PENDING, s.list().entries().get(0).status());
    }

    @Test
    void confirm_tamperedToken_doesNotFlip(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        // Flip a character; salted-hash match fails.
        String tampered = ('A' == token.charAt(0) ? 'B' : 'A') + token.substring(1);
        assertFalse(s.confirm("alice@example.com", tampered));
        assertFalse(s.isConfirmed("alice@example.com"));
    }

    @Test
    void confirm_forThirdParty_withAnotherAddressToken_doesNotFlip(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String aliceToken = s.requestConsent("alice@example.com");
        s.requestConsent("bob@example.com");
        // Alice's token must not confirm bob.
        assertFalse(s.confirm("bob@example.com", aliceToken));
        assertFalse(s.isConfirmed("bob@example.com"));
    }

    @Test
    void confirm_expiredToken_doesNotFlip(@TempDir Path home) {
        // Negative TTL => tokenExpiresAt is unambiguously in the past, so expiry is deterministic.
        // (A zero TTL risks a same-millisecond flake: it only reads as expired if confirm lands in a
        // strictly later ms than the request.)
        RecipientConsentStore s = new RecipientConsentStore(home, -1000L);
        String token = s.requestConsent("alice@example.com");
        assertFalse(s.confirm("alice@example.com", token));
        assertFalse(s.isConfirmed("alice@example.com"));
    }

    @Test
    void confirm_unknownAddress_returnsFalse(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        assertFalse(s.confirm("nobody@example.com", "whatever"));
    }

    @Test
    void confirm_isIdempotent(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        assertTrue(s.confirm("alice@example.com", token));
        // Re-confirm is a no-op success; state stays CONFIRMED.
        assertTrue(s.confirm("alice@example.com", token));
        assertTrue(s.isConfirmed("alice@example.com"));
        assertEquals(1, s.list().count());
    }

    @Test
    void storedTokenHash_isNotReversibleToRawToken(@TempDir Path home) throws Exception {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        String onDisk = Files.readString(home.resolve("mail-consent.json"));
        // The raw token must NEVER appear on disk — only its salted hash.
        assertFalse(onDisk.contains(token), "raw token must not be persisted");
        // The address is stored (needed for lookup) but the token is not recoverable.
        assertTrue(onDisk.contains("tokenHash"));
    }

    @Test
    void revoke_movesToRevoked_andNeverConfirms(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("alice@example.com");
        assertTrue(s.revoke("alice@example.com"));
        assertFalse(s.isConfirmed("alice@example.com"));
        assertEquals(ConsentStatus.REVOKED, s.list().entries().get(0).status());
        // A REVOKED entry never confirms, even with the original token.
        assertFalse(s.confirm("alice@example.com", token));
        assertFalse(s.isConfirmed("alice@example.com"));
    }

    @Test
    void revoke_unknownAddress_returnsFalse(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        assertFalse(s.revoke("nobody@example.com"));
    }

    @Test
    void requestConsent_isIdempotentUpsert_reMintsFreshToken(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String first = s.requestConsent("alice@example.com");
        String second = s.requestConsent("alice@example.com");
        assertNotEquals(first, second, "re-request mints a superseding token");
        // Only ONE entry — an upsert, not a duplicate.
        assertEquals(1, s.list().count());
        // The superseding (second) token confirms; the stale first must not.
        assertFalse(s.confirm("alice@example.com", first));
        assertTrue(s.confirm("alice@example.com", second));
    }

    @Test
    void normalises_caseAndCrlf(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        String token = s.requestConsent("Alice@Example.COM");
        // A cosmetic variant is the SAME address for confirm + query.
        assertTrue(s.confirm("  ALICE@example.com  ", token));
        assertTrue(s.isConfirmed("alice@example.com"));
        // A CRLF-injected variant collapses to invalid -> not confirmed.
        assertFalse(s.isConfirmed("alice@example.com\r\nX: y"));
    }

    @Test
    void requestConsent_dropsNullBlankMalformed(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        assertNull(s.requestConsent(null));
        assertNull(s.requestConsent(""));
        assertNull(s.requestConsent("   "));
        assertNull(s.requestConsent("not-an-email"));
        assertNull(s.requestConsent("bad\r\ninj@example.com"));
        assertEquals(0, s.list().count());
    }

    @Test
    void list_masksAddresses_neverRaw_neverHash(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        s.requestConsent("alice@example.com");
        ConsentView.Entry e = s.list().entries().get(0);
        assertEquals("a***@example.com", e.maskedAddress());
        assertFalse(e.maskedAddress().contains("alice"));
        // The view record has no token hash/salt accessor at all — it can't leak.
    }

    @Test
    void atomicWrite_leavesNoTempFile(@TempDir Path home) {
        RecipientConsentStore s = store(home);
        s.requestConsent("alice@example.com");
        assertTrue(Files.isRegularFile(home.resolve("mail-consent.json")));
        assertFalse(Files.exists(home.resolve("mail-consent.json.tmp")), "temp file must be moved, not left");
    }

    @Test
    void inMemoryFallback_whenNoHome_neverTouchesDisk(@TempDir Path home) {
        RecipientConsentStore s = new RecipientConsentStore(null);
        String token = s.requestConsent("alice@example.com");
        assertNotNull(token);
        assertFalse(s.exists());
        assertTrue(s.confirm("alice@example.com", token));
        assertTrue(s.isConfirmed("alice@example.com"));
        assertFalse(Files.exists(home.resolve("mail-consent.json")));
    }

    @Test
    void corruptFile_readsAsEmpty_neverThrows(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("mail-consent.json"), "{ this is not valid json");
        RecipientConsentStore s = store(home);
        assertDoesNotThrow(() -> s.list());
        assertFalse(s.isConfirmed("alice@example.com"));
    }
}
