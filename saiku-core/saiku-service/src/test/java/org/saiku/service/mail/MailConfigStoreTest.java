package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P0-A: the writable, encrypted-at-rest, write-only mail-config store (saiku#943 Phase 0).
 *
 * <p>These tests lock the security-load-bearing behaviour SEC gates: the SMTP password is encrypted
 * on disk (never plaintext), is never handed back to a reader (write-only), yet round-trips to the
 * correct plaintext for the sender.
 */
class MailConfigStoreTest {

    private static final String SECRET = "hunter2-smtp-pw";

    // Point the per-install encryption key (InstallKeyProvider) at a scratch saiku.home so the test
    // never writes a secret.key into the real ./saiku-home. Encrypt+decrypt within the test use the
    // same resolved key, so the round-trip holds regardless of which home supplied it.
    private String savedHome;

    @BeforeEach
    void isolateHome(@TempDir Path keyHome) {
        savedHome = System.getProperty("saiku.home");
        System.setProperty("saiku.home", keyHome.toString());
    }

    @AfterEach
    void restoreHome() {
        if (savedHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", savedHome);
        }
    }

    private MailConfigStore store(Path home) {
        return new MailConfigStore(home);
    }

    private void saveTypical(MailConfigStore s, String password) {
        s.save(
                "smtp.example.com",
                587,
                "mailer@example.com",
                password,
                "reports@example.com",
                true,
                false,
                "admin@example.com");
    }

    @Test
    void missingFile_readsAsEmpty(@TempDir Path home) {
        MailConfigStore s = store(home);
        assertFalse(s.exists());
        assertTrue(s.read().isEmpty());
        assertTrue(s.readView().isEmpty());
        assertTrue(s.toMailConfig().isEmpty());
    }

    @Test
    void save_writesFile_andRoundTripsToPlaintextForTheSender(@TempDir Path home) {
        MailConfigStore s = store(home);
        saveTypical(s, SECRET);

        assertTrue(s.exists());
        MailConfig cfg = s.toMailConfig().orElseThrow();
        assertEquals("smtp.example.com", cfg.host());
        assertEquals(587, cfg.port());
        assertEquals("reports@example.com", cfg.from());
        assertEquals("admin@example.com", cfg.selfTo());
        // The sender gets the real plaintext back, decrypted.
        assertEquals(SECRET, cfg.password());
        assertTrue(cfg.isConfigured());
    }

    @Test
    void password_isEncryptedAtRest_neverPlaintextOnDisk(@TempDir Path home) throws Exception {
        MailConfigStore s = store(home);
        saveTypical(s, SECRET);

        String onDisk = Files.readString(home.resolve("mail-config.json"));
        // The raw file must NOT contain the plaintext secret anywhere.
        assertFalse(onDisk.contains(SECRET), "plaintext password must never be written to disk");
        // The stored password field is the in-tree AES-256-GCM value (v2:-prefixed ciphertext).
        MailConfigFile raw = s.read().orElseThrow();
        assertNotNull(raw.getPassword());
        assertNotEquals(SECRET, raw.getPassword());
        assertTrue(raw.getPassword().startsWith("v2:"), "stored password must be AES-GCM (v2:) ciphertext");
    }

    @Test
    void readView_isWriteOnly_neverExposesThePassword(@TempDir Path home) {
        MailConfigStore s = store(home);
        saveTypical(s, SECRET);

        MailConfigView view = s.readView().orElseThrow();
        // The view carries every field via passwordSet — but there is no accessor that returns the
        // password at all. Assert the flag reflects presence without disclosing the value.
        assertTrue(view.passwordSet());
        assertEquals("smtp.example.com", view.host());
        assertEquals("mailer@example.com", view.username());
        assertEquals("admin@example.com", view.selfTo());
        // Defence-in-depth: the record's string form must not leak the secret or its ciphertext.
        assertFalse(view.toString().contains(SECRET));
        MailConfigFile raw = s.read().orElseThrow();
        assertFalse(view.toString().contains(raw.getPassword()));
    }

    @Test
    void blankPasswordOnResave_keepsExistingSecret(@TempDir Path home) {
        MailConfigStore s = store(home);
        saveTypical(s, SECRET);
        String cipherBefore = s.read().orElseThrow().getPassword();

        // Re-save changing a non-secret field, with a BLANK password → must keep the old secret.
        s.save(
                "smtp.example.com",
                2525,
                "mailer@example.com",
                "  ",
                "reports@example.com",
                true,
                false,
                "admin@example.com");

        MailConfigFile after = s.read().orElseThrow();
        assertEquals(2525, after.getPort(), "non-secret field updated");
        assertEquals(cipherBefore, after.getPassword(), "blank password must preserve the stored secret");
        assertEquals(SECRET, s.toMailConfig().orElseThrow().password(), "secret still decrypts correctly");
    }

    @Test
    void noPasswordEverSet_passwordSetIsFalse_andConfigHasNoPassword(@TempDir Path home) {
        MailConfigStore s = store(home);
        s.save("smtp.example.com", 587, null, null, "reports@example.com", true, false, "admin@example.com");

        assertFalse(s.readView().orElseThrow().passwordSet());
        assertNull(s.read().orElseThrow().getPassword());
        assertNull(s.toMailConfig().orElseThrow().password());
    }

    @Test
    void save_returnsRedactedView_notThePassword(@TempDir Path home) {
        MailConfigStore s = store(home);
        MailConfigView returned =
                s.save("smtp.example.com", 587, "u", SECRET, "reports@example.com", false, true, "admin@example.com");
        assertTrue(returned.passwordSet());
        assertTrue(returned.ssl());
        assertFalse(returned.startTls());
        assertFalse(returned.toString().contains(SECRET));
    }

    @Test
    void blankFieldsAreNormalisedToNull(@TempDir Path home) {
        MailConfigStore s = store(home);
        s.save("  ", 587, "   ", SECRET, "  ", true, false, "  ");
        Optional<MailConfigFile> raw = s.read();
        assertTrue(raw.isPresent());
        assertNull(raw.get().getHost());
        assertNull(raw.get().getUsername());
        assertNull(raw.get().getFrom());
        assertNull(raw.get().getSelfTo());
    }
}
