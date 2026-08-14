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
 * saiku#1811 PR2 — the writable, file-backed recipient suppression store. Locks: fail-safe add-only
 * behaviour, idempotent suppress, normalisation (case/CRLF), the redacted (masked) list view, atomic
 * write, in-memory fallback, and corrupt-file resilience.
 *
 * <p>The store is DORMANT (no send path consults it yet) so these tests exercise it directly.
 */
class SuppressionStoreTest {

    private SuppressionStore store(Path home) {
        return new SuppressionStore(home);
    }

    @Test
    void missingFile_readsAsEmpty_andSuppressesNobody(@TempDir Path home) {
        SuppressionStore s = store(home);
        assertFalse(s.exists());
        assertTrue(s.read().isEmpty());
        assertFalse(s.isSuppressed("anyone@acme.com"));
        SuppressionView v = s.list();
        assertTrue(v.entries().isEmpty());
        assertEquals(0, v.count());
    }

    @Test
    void suppress_roundTripsAndPersists(@TempDir Path home) {
        SuppressionStore s = store(home);
        assertTrue(s.suppress("alice@example.com", "unsubscribe"));
        assertTrue(s.exists());
        assertTrue(s.isSuppressed("alice@example.com"));

        // A fresh store over the same home reads the persisted suppression back.
        SuppressionStore reopened = store(home);
        assertTrue(reopened.isSuppressed("alice@example.com"));
    }

    @Test
    void suppress_isIdempotent(@TempDir Path home) {
        SuppressionStore s = store(home);
        assertTrue(s.suppress("alice@example.com", "unsubscribe"), "first suppress adds");
        assertFalse(s.suppress("alice@example.com", "unsubscribe"), "second suppress is a no-op");
        // A cosmetic variant is the SAME address — still a no-op, never a duplicate.
        assertFalse(s.suppress("ALICE@Example.COM", "unsubscribe"));
        assertEquals(1, s.list().count());
    }

    @Test
    void isSuppressed_normalisesCaseAndCrlf(@TempDir Path home) {
        SuppressionStore s = store(home);
        s.suppress("alice@example.com", "unsubscribe");
        assertTrue(s.isSuppressed("alice@example.com"));
        assertTrue(s.isSuppressed("ALICE@EXAMPLE.COM"));
        assertTrue(s.isSuppressed("  Alice@Example.com  "));
        // CRLF-injected variant collapses to spaces -> invalid -> not suppressed (and never matches).
        assertFalse(s.isSuppressed("alice@example.com\r\nX: y"));
        assertFalse(s.isSuppressed("bob@example.com"));
    }

    @Test
    void suppress_dropsNullBlankMalformed(@TempDir Path home) {
        SuppressionStore s = store(home);
        assertFalse(s.suppress(null, "unsubscribe"));
        assertFalse(s.suppress("", "unsubscribe"));
        assertFalse(s.suppress("   ", "unsubscribe"));
        assertFalse(s.suppress("not-an-email", "unsubscribe"));
        assertFalse(s.suppress("bad\r\ninj@example.com", "unsubscribe"));
        assertEquals(0, s.list().count());
    }

    @Test
    void list_masksAddresses_neverRaw(@TempDir Path home) {
        SuppressionStore s = store(home);
        s.suppress("alice@example.com", "unsubscribe");
        SuppressionView v = s.list();
        assertEquals(1, v.count());
        SuppressionView.Entry e = v.entries().get(0);
        assertEquals("a***@example.com", e.maskedAddress());
        assertEquals("unsubscribe", e.reason());
        assertTrue(e.timestamp() > 0);
        // The raw local part is never present in the view.
        assertFalse(e.maskedAddress().contains("alice"));
    }

    @Test
    void atomicWrite_leavesNoTempFile(@TempDir Path home) throws Exception {
        SuppressionStore s = store(home);
        s.suppress("alice@example.com", "unsubscribe");
        Path file = home.resolve("mail-suppression.json");
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(home.resolve("mail-suppression.json.tmp")), "temp file must be moved, not left");
    }

    @Test
    void inMemoryFallback_whenNoHome_neverTouchesDisk(@TempDir Path home) {
        SuppressionStore s = new SuppressionStore(null);
        assertTrue(s.suppress("alice@example.com", "unsubscribe"));
        assertFalse(s.exists());
        assertTrue(s.isSuppressed("alice@example.com"));
        assertFalse(s.suppress("alice@example.com", "unsubscribe"), "still idempotent in-memory");
        assertFalse(Files.exists(home.resolve("mail-suppression.json")));
    }

    @Test
    void corruptFile_readsAsEmpty_neverThrows(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("mail-suppression.json"), "{ this is not valid json");
        SuppressionStore s = store(home);
        assertDoesNotThrow(() -> s.list());
        assertFalse(s.isSuppressed("alice@example.com"));
    }
}
