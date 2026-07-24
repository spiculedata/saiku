package org.saiku.service.mail;

import java.util.List;
import java.util.Objects;

/** An email to send: HTML body, optional inline images (cid), optional attachments. */
public record MailMessage(
        String to,
        String from,
        String subject,
        String htmlBody,
        List<InlineImage> inlineImages,
        List<Attachment> attachments) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(htmlBody, "htmlBody");
        inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
