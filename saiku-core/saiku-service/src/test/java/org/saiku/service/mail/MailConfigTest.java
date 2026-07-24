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
    void fromEnvironment_resolvesWithoutThrowing() {
        MailConfig c = MailConfig.fromEnvironment();
        assertNotNull(c);
        // With no env/props set in the test JVM it is simply not configured; the point is it resolves.
        assertEquals(587, c.port());
    }
}
