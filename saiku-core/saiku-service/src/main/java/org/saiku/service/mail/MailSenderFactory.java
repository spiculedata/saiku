/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the mail sender from configuration and logs the active posture once. */
public final class MailSenderFactory {

    private static final Logger log = LoggerFactory.getLogger(MailSenderFactory.class);

    private MailSenderFactory() {}

    /** SMTP when host + from are set; otherwise the noop default. */
    public static MailSender create(MailConfig config) {
        return config.isConfigured() ? new SmtpMailSender(config) : new NoopMailSender();
    }

    /** Production factory-method (Spring): resolve env/props, log posture, build the sender. */
    public static MailSender fromEnvironment() {
        MailConfig config = MailConfig.resolve(System::getenv, System::getProperty);
        if (config.isConfigured()) {
            log.info("Email: SMTP -> {}:{} (from={})", config.host(), config.port(), config.from());
        } else {
            log.warn("Email: disabled (noop) — set SAIKU_MAIL_SMTP_HOST and SAIKU_MAIL_FROM to enable delivery.");
        }
        return create(config);
    }
}
