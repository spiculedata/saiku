/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.saiku.service.mail.Attachment;
import org.saiku.service.mail.InlineImage;
import org.saiku.service.mail.MailMessage;

/**
 * Builds a {@link MailMessage} from a validated {@link EmailSelfRequest}. No rendering — the
 * browser supplies the chart PNG and PDF; this validates, decodes, and assembles.
 */
public class EmailMessageAssembler {

    /** Per-artifact decoded-size cap (bytes). */
    static final int MAX_ARTIFACT_BYTES = 15 * 1024 * 1024;

    private static final String CHART_CID = "chart";

    /**
     * Allowlist policy for the client-supplied {@code summaryHtml}: formatting (b/i/strong/em/u/
     * etc), block structure (p/div/h1-6/ul/ol/li/blockquote/pre), links (restricted to safe
     * protocols, {@code rel=nofollow} added), and tables. Deliberately NO {@code IMAGES} — the AI
     * summary must not be able to carry a client {@code <img>} (tracking pixel). The server's own
     * chart {@code <img cid:chart>} is appended after sanitization and is unaffected. Scripts,
     * event handlers, and {@code javascript:}/{@code data:} URLs are stripped by default; none of
     * these policies allow them.
     */
    private static final PolicyFactory SUMMARY_POLICY =
            Sanitizers.FORMATTING.and(Sanitizers.BLOCKS).and(Sanitizers.LINKS).and(Sanitizers.TABLES);

    public MailMessage assemble(EmailSelfRequest req, String fromAddress, String toAddress) {
        String to = validateAddress(toAddress);
        Content c = assembleContent(req);
        return MailMessage.of(to, fromAddress, c.subject(), c.htmlBody(), c.inlineImages(), c.attachments());
    }

    /**
     * Validate + sanitize + decode the client artifacts into a recipient-agnostic {@link Content} (no
     * {@code To:}/{@code From:} bound). Reused by the multi-recipient send path (saiku#1811, PR4), which
     * delivers the SAME sanitized content to every cleared recipient, individually addressed. The exact
     * same OWASP-sanitized summary + size-capped chart/PDF discipline as {@link #assemble} applies.
     */
    public Content assembleContent(EmailSelfRequest req) {
        String subject = stripCrlf(req.getSubject() == null ? "" : req.getSubject());

        String summary = req.getSummaryHtml() == null || req.getSummaryHtml().isBlank()
                ? "<p>Your Saiku analysis is attached.</p>"
                : SUMMARY_POLICY.sanitize(req.getSummaryHtml());
        StringBuilder html = new StringBuilder(summary);

        List<InlineImage> inline = new ArrayList<>();
        if (notBlank(req.getChartPngBase64())) {
            byte[] png = decodeCapped(req.getChartPngBase64(), "chart");
            inline.add(new InlineImage(CHART_CID, "image/png", png));
            html.append("<br><img src=\"cid:").append(CHART_CID).append("\" alt=\"chart\">");
        }

        List<Attachment> attachments = new ArrayList<>();
        if (notBlank(req.getPdfBase64())) {
            byte[] pdf = decodeCapped(req.getPdfBase64(), "pdf");
            attachments.add(new Attachment("analysis.pdf", "application/pdf", pdf));
        }

        return new Content(subject, html.toString(), inline, attachments);
    }

    /** Recipient-agnostic composed content: sanitized subject + HTML body + inline images + attachments. */
    public record Content(
            String subject, String htmlBody, List<InlineImage> inlineImages, List<Attachment> attachments) {}

    private String validateAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new EmailRequestException("address is required");
        }
        try {
            InternetAddress ia = new InternetAddress(address.trim(), true);
            ia.validate();
            return ia.getAddress();
        } catch (AddressException e) {
            throw new EmailRequestException("invalid email address");
        }
    }

    private byte[] decodeCapped(String base64, String what) {
        // Fast-fail before allocating: base64 decodes to ~3/4 its length.
        if ((long) base64.length() * 3 / 4 > MAX_ARTIFACT_BYTES) {
            throw new EmailRequestException(what + " exceeds the " + MAX_ARTIFACT_BYTES + "-byte limit");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new EmailRequestException("invalid base64 for " + what);
        }
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new EmailRequestException(what + " exceeds the " + MAX_ARTIFACT_BYTES + "-byte limit");
        }
        return bytes;
    }

    private static String stripCrlf(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
