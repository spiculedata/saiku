package org.saiku.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default sender when no SMTP is configured: logs a warning and drops the message. */
public class NoopMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(NoopMailSender.class);

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void send(MailMessage message) {
        log.warn("Email is not configured (SMTP host unset) — dropping message to {} (subject: {}). "
                + "Set SAIKU_MAIL_SMTP_HOST and SAIKU_MAIL_FROM to enable delivery.",
                message.to(), message.subject());
    }
}
