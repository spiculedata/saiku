/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MailConfigTest {

    private static Function<String, String> of(Map<String, String> m) {
        return m::get;
    }

    @Test
    void unset_isNotConfigured_withSaneDefaults() {
        MailConfig c = MailConfig.resolve(of(Map.of()), of(Map.of()));
        assertFalse(c.isConfigured());
        assertEquals(587, c.port());
        assertTrue(c.startTls());
        assertFalse(c.ssl());
    }

    @Test
    void hostAndFromPresent_isConfigured() {
        MailConfig c = MailConfig.resolve(
                of(Map.of("SAIKU_MAIL_SMTP_HOST", "smtp.example.com", "SAIKU_MAIL_FROM", "saiku@example.com")),
                of(Map.of()));
        assertTrue(c.isConfigured());
        assertEquals("smtp.example.com", c.host());
        assertEquals("saiku@example.com", c.from());
    }

    @Test
    void envWinsOverProperty() {
        MailConfig c = MailConfig.resolve(
                of(Map.of("SAIKU_MAIL_SMTP_HOST", "env-host", "SAIKU_MAIL_FROM", "e@x.com")),
                of(Map.of("saiku.mail.smtp.host", "prop-host", "saiku.mail.from", "p@x.com")));
        assertEquals("env-host", c.host());
        assertEquals("e@x.com", c.from());
    }

    @Test
    void propertyUsedWhenEnvAbsent_andPortParsed() {
        MailConfig c = MailConfig.resolve(
                of(Map.of()),
                of(Map.of(
                        "saiku.mail.smtp.host",
                        "prop-host",
                        "saiku.mail.from",
                        "p@x.com",
                        "saiku.mail.smtp.port",
                        "2525")));
        assertEquals("prop-host", c.host());
        assertEquals(2525, c.port());
    }

    @Test
    void selfTo_unset_isNotSelfSendConfigured() {
        MailConfig c = MailConfig.resolve(of(Map.of()), of(Map.of()));
        assertNull(c.selfTo());
        assertFalse(c.selfSendConfigured());
    }

    @Test
    void selfTo_fromProperty_isSelfSendConfigured() {
        MailConfig c = MailConfig.resolve(of(Map.of()), of(Map.of("saiku.mail.self.to", "ops@example.com")));
        assertEquals("ops@example.com", c.selfTo());
        assertTrue(c.selfSendConfigured());
    }

    @Test
    void selfTo_fromEnv_isSelfSendConfigured() {
        MailConfig c = MailConfig.resolve(of(Map.of("SAIKU_MAIL_SELF_TO", "ops@example.com")), of(Map.of()));
        assertEquals("ops@example.com", c.selfTo());
        assertTrue(c.selfSendConfigured());
    }

    @Test
    void selfTo_envWinsOverProperty() {
        MailConfig c = MailConfig.resolve(
                of(Map.of("SAIKU_MAIL_SELF_TO", "env@example.com")),
                of(Map.of("saiku.mail.self.to", "prop@example.com")));
        assertEquals("env@example.com", c.selfTo());
    }

    @Test
    void selfTo_blank_isNotSelfSendConfigured() {
        MailConfig c = MailConfig.resolve(of(Map.of()), of(Map.of("saiku.mail.self.to", "   ")));
        // pick() trims and treats blank as absent, so selfTo falls through to the null default.
        assertNull(c.selfTo());
        assertFalse(c.selfSendConfigured());
    }

    @Test
    void fromEnvironment_resolvesWithoutThrowing() {
        MailConfig c = MailConfig.fromEnvironment();
        assertNotNull(c);
        // With no env/props set in the test JVM it is simply not configured; the point is it resolves.
        assertEquals(587, c.port());
    }
}
