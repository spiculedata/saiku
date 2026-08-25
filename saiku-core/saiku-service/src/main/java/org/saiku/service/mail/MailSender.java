/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

/**
 * Sends an email. The default binding is {@link NoopMailSender} (mail disabled);
 * a configured deployment gets {@link SmtpMailSender}. Chosen by {@link MailSenderFactory}.
 */
public interface MailSender {

    /** True when a real transport is configured; false for the noop default. */
    boolean isConfigured();

    /** Deliver the message. Throws {@link MailException} on transport failure. */
    void send(MailMessage message) throws MailException;
}
