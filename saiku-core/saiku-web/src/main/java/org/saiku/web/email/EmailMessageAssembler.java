package org.saiku.web.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    public MailMessage assemble(EmailSelfRequest req, String fromAddress) {
        String to = validateAddress(req.getAddress());
        String subject = stripCrlf(req.getSubject() == null ? "" : req.getSubject());

        StringBuilder html = new StringBuilder(
                req.getSummaryHtml() == null || req.getSummaryHtml().isBlank()
                        ? "<p>Your Saiku analysis is attached.</p>"
                        : req.getSummaryHtml());

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

        return new MailMessage(to, fromAddress, subject, html.toString(), inline, attachments);
    }

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
