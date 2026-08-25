/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import java.util.List;
import java.util.Objects;

/**
 * An email to send: HTML body, optional inline images (cid), optional attachments.
 *
 * <p>{@code listUnsubscribe} (saiku#1811, PR2) is an OPTIONAL RFC 2369 {@code List-Unsubscribe}
 * header value (e.g. {@code "<https://host/rest/saiku/mail/unsubscribe?...>"}). It is <b>additive and
 * null-safe</b>: when null, {@link SmtpMailSender} emits no unsubscribe headers and the built MIME
 * message is byte-for-byte identical to the pre-PR2 self-send/test paths. When set, the sender emits
 * both {@code List-Unsubscribe} and {@code List-Unsubscribe-Post: List-Unsubscribe=One-Click}
 * (RFC 8058 one-click). The 6-arg factory {@link #of} preserves every existing call site.
 */
public record MailMessage(
        String to,
        String from,
        String subject,
        String htmlBody,
        List<InlineImage> inlineImages,
        List<Attachment> attachments,
        String listUnsubscribe) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(htmlBody, "htmlBody");
        inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        // listUnsubscribe intentionally left as-is (nullable) — a null means "emit no unsubscribe
        // header", preserving the existing self-send/test output byte-for-byte.
    }

    /**
     * Back-compat factory for the existing 6-field callers (no {@code List-Unsubscribe}). Equivalent
     * to the full constructor with a null {@code listUnsubscribe}.
     */
    public static MailMessage of(
            String to,
            String from,
            String subject,
            String htmlBody,
            List<InlineImage> inlineImages,
            List<Attachment> attachments) {
        return new MailMessage(to, from, subject, htmlBody, inlineImages, attachments, null);
    }
}
