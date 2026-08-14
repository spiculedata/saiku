package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SmtpMailSenderTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sends_multipartEmail_withInlineChart_andPdfAttachment() throws Exception {
        MailConfig cfg = new MailConfig(
                "127.0.0.1", greenMail.getSmtp().getPort(), null, null, "saiku@example.com", false, false, null);
        SmtpMailSender sender = new SmtpMailSender(cfg);

        MailMessage m = MailMessage.of(
                "me@example.com",
                "saiku@example.com",
                "Your analysis",
                "<html><body><p>Summary</p><img src=\"cid:chart\"></body></html>",
                List.of(new InlineImage("chart", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47})),
                List.of(new Attachment("analysis.pdf", "application/pdf", "%PDF-1.4".getBytes())));

        assertTrue(sender.isConfigured());
        sender.send(m);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertEquals("Your analysis", received.getSubject());
        assertEquals("me@example.com", received.getAllRecipients()[0].toString());

        MimeMultipart mixed = (MimeMultipart) received.getContent();
        assertTrue(mixed.getContentType().toLowerCase().contains("multipart/mixed"));

        // first part = related (html + inline); assert an attachment part named analysis.pdf exists.
        boolean pdfFound = false;
        boolean relatedFound = false;
        for (int i = 0; i < mixed.getCount(); i++) {
            String ct = mixed.getBodyPart(i).getContentType().toLowerCase();
            String fn = mixed.getBodyPart(i).getFileName();
            if (ct.contains("multipart/related")) relatedFound = true;
            if ("analysis.pdf".equals(fn)) pdfFound = true;
        }
        assertTrue(relatedFound, "expected a multipart/related part");
        assertTrue(pdfFound, "expected an analysis.pdf attachment");

        // Descend into the related part and confirm the inline chart image is actually present.
        boolean inlineChartFound = false;
        for (int i = 0; i < mixed.getCount(); i++) {
            Object content = mixed.getBodyPart(i).getContent();
            if (content instanceof MimeMultipart related) {
                for (int j = 0; j < related.getCount(); j++) {
                    BodyPart part = related.getBodyPart(j);
                    String[] cid = part.getHeader("Content-ID");
                    String ct = part.getContentType().toLowerCase();
                    if (cid != null && cid.length > 0 && cid[0].contains("chart") && ct.contains("image/png")) {
                        inlineChartFound = true;
                    }
                }
            }
        }
        assertTrue(inlineChartFound, "expected an inline image/png with Content-ID <chart> inside the related part");
    }

    @Test
    void nullListUnsubscribe_emitsNoUnsubscribeHeaders() throws Exception {
        // saiku#1811 PR2: the existing self-send/test paths pass a null List-Unsubscribe. Assert the
        // sender emits NEITHER unsubscribe header — the message is unchanged from before PR2.
        MailConfig cfg = new MailConfig(
                "127.0.0.1", greenMail.getSmtp().getPort(), null, null, "saiku@example.com", false, false, null);
        SmtpMailSender sender = new SmtpMailSender(cfg);

        MailMessage m =
                MailMessage.of("me@example.com", "saiku@example.com", "No unsub", "<p>hi</p>", List.of(), List.of());
        sender.send(m);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertNull(received.getHeader("List-Unsubscribe"), "no List-Unsubscribe header when value is null");
        assertNull(received.getHeader("List-Unsubscribe-Post"), "no List-Unsubscribe-Post header when value is null");
    }

    @Test
    void setListUnsubscribe_emitsBothRfc8058Headers() throws Exception {
        MailConfig cfg = new MailConfig(
                "127.0.0.1", greenMail.getSmtp().getPort(), null, null, "saiku@example.com", false, false, null);
        SmtpMailSender sender = new SmtpMailSender(cfg);

        String unsub = "<https://host/rest/saiku/mail/unsubscribe?address=me%40example.com&token=abc>";
        MailMessage m = new MailMessage(
                "me@example.com", "saiku@example.com", "With unsub", "<p>hi</p>", List.of(), List.of(), unsub);
        sender.send(m);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        String[] lu = received.getHeader("List-Unsubscribe");
        assertNotNull(lu);
        assertEquals(unsub, lu[0]);
        String[] lup = received.getHeader("List-Unsubscribe-Post");
        assertNotNull(lup);
        assertEquals("List-Unsubscribe=One-Click", lup[0]);
    }
}
