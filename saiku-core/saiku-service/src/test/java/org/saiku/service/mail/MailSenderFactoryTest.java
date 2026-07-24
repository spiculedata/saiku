package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MailSenderFactoryTest {

    @Test
    void unconfigured_yieldsNoop() {
        MailConfig cfg = new MailConfig(null, 587, null, null, null, true, false, null);
        MailSender s = MailSenderFactory.create(cfg);
        assertTrue(s instanceof NoopMailSender);
        assertFalse(s.isConfigured());
    }

    @Test
    void configured_yieldsSmtp() {
        MailConfig cfg = new MailConfig("smtp.example.com", 587, null, null, "saiku@example.com", true, false, null);
        MailSender s = MailSenderFactory.create(cfg);
        assertTrue(s instanceof SmtpMailSender);
        assertTrue(s.isConfigured());
    }
}
