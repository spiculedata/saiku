package org.saiku.service.mail.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1811 PR1 — the writable, file-backed recipient allowlist store. Locks the load-bearing
 * behaviour: fail-closed default (empty store allows nobody), exact host-suffix domain match (so
 * {@code evil-acme.com} can't bypass {@code acme.com}), normalisation (lowercase / CRLF-strip / RFC
 * validation), round-trip, and the redacted view.
 *
 * <p>This store is DORMANT — no send path calls it — so these tests exercise the store directly.
 */
class RecipientTrustStoreTest {

    private RecipientTrustStore store(Path home) {
        return new RecipientTrustStore(home);
    }

    @Test
    void missingFile_readsAsEmpty_andAllowsNobody(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        assertFalse(s.exists());
        assertTrue(s.read().isEmpty());
        // Fail-closed default: nothing is allowed until an admin adds an entry.
        assertFalse(s.isAllowed("anyone@acme.com"));
        RecipientTrustView view = s.readView();
        assertTrue(view.addresses().isEmpty());
        assertTrue(view.domains().isEmpty());
        assertEquals(0, view.count());
    }

    @Test
    void save_roundTripsAndPersists(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        RecipientTrustView view = s.save(List.of("alice@example.com"), List.of("acme.com"));

        assertTrue(s.exists());
        assertEquals(List.of("alice@example.com"), view.addresses());
        assertEquals(List.of("acme.com"), view.domains());
        assertEquals(2, view.count());

        // A fresh store over the same home reads the persisted allowlist back.
        RecipientTrustStore reopened = store(home);
        RecipientTrustView reread = reopened.readView();
        assertEquals(List.of("alice@example.com"), reread.addresses());
        assertEquals(List.of("acme.com"), reread.domains());
    }

    @Test
    void explicitAddress_isAllowed(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        s.save(List.of("alice@example.com"), List.of());
        assertTrue(s.isAllowed("alice@example.com"));
        // Case-insensitive: the query is normalised the same way as storage.
        assertTrue(s.isAllowed("ALICE@Example.COM"));
        // A different address on the same host is NOT allowed (no domain entry).
        assertFalse(s.isAllowed("bob@example.com"));
    }

    @Test
    void domainSuffix_isAllowed_forExactHostOnly(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        s.save(List.of(), List.of("acme.com"));
        assertTrue(s.isAllowed("x@acme.com"));
        assertTrue(s.isAllowed("someone.else@acme.com"));
    }

    @Test
    void lookalikeDomain_isRejected_noSuffixBypass(@TempDir Path home) {
        // THE load-bearing bypass-closure: an exact host match only, never a string suffix.
        RecipientTrustStore s = store(home);
        s.save(List.of(), List.of("acme.com"));
        assertFalse(s.isAllowed("x@evil-acme.com"), "evil-acme.com must NOT match acme.com");
        assertFalse(s.isAllowed("x@notacme.com"));
        assertFalse(s.isAllowed("x@acme.com.evil.com"), "subdomain-of-attacker must NOT match");
        assertFalse(s.isAllowed("x@acme.co"));
    }

    @Test
    void crlfAndInvalidAddresses_areDroppedOnSave(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        RecipientTrustView view = s.save(
                List.of(
                        "good@example.com",
                        "bad\r\ninjected@example.com", // CRLF -> collapsed to spaces -> invalid -> dropped
                        "not-an-email", // no @ -> invalid -> dropped
                        "   "), // blank -> dropped
                List.of(
                        "acme.com",
                        "no-dot-host", // invalid domain -> dropped
                        "@leading-at.com", // leading @ stripped -> kept as leading-at.com
                        "has@symbol.com")); // '@' in a domain -> invalid -> dropped

        assertEquals(List.of("good@example.com"), view.addresses());
        assertEquals(List.of("acme.com", "leading-at.com"), view.domains());
        assertFalse(s.isAllowed("bad@example.com"));
    }

    @Test
    void emptyStore_afterSave_stillAllowsNobody(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        // Saving empty lists is legal and means "allow nobody".
        RecipientTrustView view = s.save(List.of(), List.of());
        assertEquals(0, view.count());
        assertFalse(s.isAllowed("alice@example.com"));
        assertFalse(s.isAllowed("x@acme.com"));
    }

    @Test
    void save_normalisesToLowercase_andDedupes(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        RecipientTrustView view = s.save(
                List.of("Alice@Example.com", "alice@example.com", "BOB@EXAMPLE.COM"), List.of("Acme.com", "ACME.COM"));
        assertEquals(List.of("alice@example.com", "bob@example.com"), view.addresses());
        assertEquals(List.of("acme.com"), view.domains());
    }

    @Test
    void isAllowed_isFailClosed_forNullBlankAndMalformed(@TempDir Path home) {
        RecipientTrustStore s = store(home);
        s.save(List.of("alice@example.com"), List.of("acme.com"));
        assertFalse(s.isAllowed(null));
        assertFalse(s.isAllowed(""));
        assertFalse(s.isAllowed("   "));
        assertFalse(s.isAllowed("not-an-email"));
        assertFalse(s.isAllowed("@acme.com"));
    }

    @Test
    void atomicWrite_leavesNoTempFileAndValidJson(@TempDir Path home) throws Exception {
        RecipientTrustStore s = store(home);
        s.save(List.of("alice@example.com"), List.of("acme.com"));
        Path file = home.resolve("mail-recipients.json");
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(home.resolve("mail-recipients.json.tmp")), "temp file must be moved, not left behind");
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("alice@example.com"));
        assertTrue(onDisk.contains("acme.com"));
    }

    @Test
    void inMemoryFallback_whenNoHome_neverTouchesDisk(@TempDir Path home) {
        // A null home => in-memory only. The allowlist still round-trips within the instance.
        RecipientTrustStore s = new RecipientTrustStore(null);
        s.save(List.of("alice@example.com"), List.of("acme.com"));
        assertFalse(s.exists());
        assertTrue(s.isAllowed("alice@example.com"));
        assertTrue(s.isAllowed("x@acme.com"));
        assertFalse(s.isAllowed("x@evil-acme.com"));
        // Nothing was written to the temp home (only fails if the store wrongly used a path).
        assertFalse(Files.exists(home.resolve("mail-recipients.json")));
    }

    @Test
    void corruptFile_readsAsEmpty_neverThrows(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("mail-recipients.json"), "{ this is not valid json");
        RecipientTrustStore s = store(home);
        assertDoesNotThrow(() -> s.readView());
        // Fail-closed on a corrupt file: allow nobody.
        assertFalse(s.isAllowed("alice@example.com"));
    }
}
