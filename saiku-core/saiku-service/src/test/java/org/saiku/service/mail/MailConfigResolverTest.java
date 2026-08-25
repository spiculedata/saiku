/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P0-B: env-wins precedence between environment/system-property config and the wizard file
 * (saiku#943). The rule Juan confirmed — a manually-set/env config ALWAYS beats the UI wizard.
 */
class MailConfigResolverTest {

    private static final String SECRET = "file-smtp-pw";

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

    private static Function<String, String> of(Map<String, String> m) {
        return m::get;
    }

    private MailConfigStore fileStore(Path home) {
        MailConfigStore s = new MailConfigStore(home);
        s.save(
                "file.smtp.example",
                2525,
                "fileuser",
                SECRET,
                "file-from@example.com",
                true,
                false,
                "file-self@example.com");
        return s;
    }

    @Test
    void noEnv_noFile_isNotManagedByOps_andEffectiveIsUnconfigured(@TempDir Path home) {
        MailConfigResolver r = new MailConfigResolver(of(Map.of()), of(Map.of()), new MailConfigStore(home));
        assertFalse(r.managedByOps());
        assertFalse(r.effective().isConfigured());
        assertFalse(r.view().managedByOps());
        assertFalse(r.view().passwordSet());
    }

    @Test
    void fileOnly_notManagedByOps_effectiveComesFromFile(@TempDir Path home) {
        MailConfigResolver r = new MailConfigResolver(of(Map.of()), of(Map.of()), fileStore(home));
        assertFalse(r.managedByOps(), "file-only config is not ops-managed — wizard stays editable");
        MailConfig eff = r.effective();
        assertEquals("file.smtp.example", eff.host());
        assertEquals(SECRET, eff.password(), "sender gets the decrypted file password");
        MailConfigView v = r.view();
        assertFalse(v.managedByOps());
        assertTrue(v.passwordSet());
        assertEquals("file.smtp.example", v.host());
    }

    @Test
    void envPresent_winsOverFile_andIsManagedByOps(@TempDir Path home) {
        // Env supplies host + from → ops-managed → env value is effective, file is ignored.
        Function<String, String> env = of(Map.of(
                "SAIKU_MAIL_SMTP_HOST", "env.smtp.example",
                "SAIKU_MAIL_FROM", "env-from@example.com"));
        MailConfigResolver r = new MailConfigResolver(env, of(Map.of()), fileStore(home));

        assertTrue(r.managedByOps(), "env config → managed by ops");
        MailConfig eff = r.effective();
        assertEquals("env.smtp.example", eff.host(), "env host WINS over the file host");
        assertEquals("env-from@example.com", eff.from());

        MailConfigView v = r.view();
        assertTrue(v.managedByOps(), "view flags ops-managed so the wizard renders read-only");
        assertEquals("env.smtp.example", v.host());
    }

    @Test
    void systemPropertyAlsoCountsAsOpsManaged(@TempDir Path home) {
        Function<String, String> prop = of(Map.of(
                "saiku.mail.smtp.host", "prop.smtp.example",
                "saiku.mail.from", "prop-from@example.com"));
        MailConfigResolver r = new MailConfigResolver(of(Map.of()), prop, fileStore(home));
        assertTrue(r.managedByOps());
        assertEquals("prop.smtp.example", r.effective().host());
    }

    @Test
    void view_neverCarriesPassword_evenWhenOpsManaged(@TempDir Path home) {
        Function<String, String> env = of(Map.of(
                "SAIKU_MAIL_SMTP_HOST", "env.smtp.example",
                "SAIKU_MAIL_FROM", "env-from@example.com",
                "SAIKU_MAIL_SMTP_PASSWORD", "env-secret-pw"));
        MailConfigResolver r = new MailConfigResolver(env, of(Map.of()), fileStore(home));
        MailConfigView v = r.view();
        assertTrue(v.passwordSet(), "passwordSet reflects presence");
        // The view has no password accessor at all; its toString must not leak the env secret.
        assertFalse(v.toString().contains("env-secret-pw"));
    }
}
