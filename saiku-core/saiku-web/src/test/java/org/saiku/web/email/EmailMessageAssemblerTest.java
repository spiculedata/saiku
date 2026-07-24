package org.saiku.web.email;

import static org.junit.Assert.*;

import java.util.Base64;
import org.junit.Test;
import org.saiku.service.mail.MailMessage;

public class EmailMessageAssemblerTest {

    private static final String FROM = "saiku@example.com";
    private static final String TO = "me@example.com";
    private final EmailMessageAssembler assembler = new EmailMessageAssembler();

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static EmailSelfRequest req() {
        EmailSelfRequest r = new EmailSelfRequest();
        r.setSubject("Your analysis");
        r.setSummaryHtml("<p>Here is what changed.</p>");
        r.setChartPngBase64(b64(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}));
        r.setPdfBase64(b64("%PDF-1.4".getBytes()));
        return r;
    }

    @Test
    public void assembles_html_inlineChart_and_pdf() {
        MailMessage m = assembler.assemble(req(), FROM, TO);
        assertEquals(TO, m.to());
        assertEquals(FROM, m.from());
        assertEquals("Your analysis", m.subject());
        assertTrue("html should reference the inline chart", m.htmlBody().contains("cid:chart"));
        assertEquals(1, m.inlineImages().size());
        assertEquals("chart", m.inlineImages().get(0).contentId());
        assertEquals("image/png", m.inlineImages().get(0).contentType());
        assertEquals(1, m.attachments().size());
        assertEquals("analysis.pdf", m.attachments().get(0).filename());
        assertEquals("application/pdf", m.attachments().get(0).contentType());
    }

    /**
     * The recipient is always the server-provided {@code to} argument. The request body no longer
     * carries any recipient field (open-relay fix, F1), so nothing the client sends can influence it.
     */
    @Test
    public void recipient_isTheServerProvidedTo() {
        MailMessage m = assembler.assemble(req(), FROM, "server-fixed@example.com");
        assertEquals("server-fixed@example.com", m.to());
    }

    @Test
    public void missingChart_isOmitted_pdfStillAttached() {
        EmailSelfRequest r = req();
        r.setChartPngBase64(null);
        MailMessage m = assembler.assemble(r, FROM, TO);
        assertTrue(m.inlineImages().isEmpty());
        assertFalse(m.htmlBody().contains("cid:chart"));
        assertEquals(1, m.attachments().size());
    }

    @Test
    public void invalidRecipient_isRejected() {
        try {
            assembler.assemble(req(), FROM, "not-an-email");
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
        try {
            assembler.assemble(req(), FROM, "");
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
        try {
            assembler.assemble(req(), FROM, null);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void crlfInRecipient_cannotInjectHeaders() {
        try {
            assembler.assemble(req(), FROM, "me@example.com\r\nBcc: victim@example.com");
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void crlfInSubject_isStripped() {
        EmailSelfRequest r = req();
        r.setSubject("Hello\r\nBcc: victim@example.com");
        MailMessage m = assembler.assemble(r, FROM, TO);
        assertFalse(m.subject().contains("\r"));
        assertFalse(m.subject().contains("\n"));
    }

    @Test
    public void oversizeArtifact_isRejected() {
        EmailSelfRequest r = req();
        r.setPdfBase64(b64(new byte[16 * 1024 * 1024])); // 16 MB > 15 MB cap
        try {
            assembler.assemble(r, FROM, TO);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void invalidBase64_isRejected() {
        EmailSelfRequest r = req();
        r.setPdfBase64("!!!not-base64!!!");
        try {
            assembler.assemble(r, FROM, TO);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void summaryHtml_stripsScriptAndEventHandlers() {
        EmailSelfRequest r = req();
        r.setSummaryHtml("<p>Hi</p><script>alert(1)</script><img src=x onerror=alert(1)>");
        MailMessage m = assembler.assemble(r, FROM, TO);
        assertTrue(m.htmlBody().contains("<p>Hi</p>"));
        assertFalse(m.htmlBody().contains("<script"));
        assertFalse(m.htmlBody().contains("onerror"));
        // Only the server-added chart <img cid:chart> may survive; the client's
        // tracking <img src=x> must not.
        assertFalse(m.htmlBody().contains("src=\"x\""));
        assertFalse(m.htmlBody().contains("src=x"));
    }

    @Test
    public void summaryHtml_stripsUnsafeLinkProtocol() {
        EmailSelfRequest r = req();
        r.setSummaryHtml("<a href=\"javascript:alert(1)\">x</a>");
        MailMessage m = assembler.assemble(r, FROM, TO);
        assertFalse(m.htmlBody().contains("javascript:"));
    }

    @Test
    public void summaryHtml_keepsSafeFormattingLinksAndTables() {
        EmailSelfRequest r = req();
        r.setSummaryHtml("<h3>Report</h3><ul><li>Small $1,500</li></ul>"
                + "<a href=\"https://x.com\">link</a><table><tr><td>1</td></tr></table>");
        MailMessage m = assembler.assemble(r, FROM, TO);
        String body = m.htmlBody();
        assertTrue(body.contains("Report"));
        assertTrue(body.contains("Small $1,500"));
        assertTrue(body.contains("https://x.com"));
        assertTrue(body.contains("<table"));
        assertTrue(body.contains(">1<"));
    }

    @Test
    public void nullOrBlankSummary_usesDefault() {
        EmailSelfRequest r1 = req();
        r1.setSummaryHtml(null);
        MailMessage m1 = assembler.assemble(r1, FROM, TO);
        assertTrue(m1.htmlBody().startsWith("<p>Your Saiku analysis is attached.</p>"));

        EmailSelfRequest r2 = req();
        r2.setSummaryHtml("   ");
        MailMessage m2 = assembler.assemble(r2, FROM, TO);
        assertTrue(m2.htmlBody().startsWith("<p>Your Saiku analysis is attached.</p>"));
    }

    @Test
    public void chartImg_stillAppendedAfterSanitizedSummary() {
        EmailSelfRequest r = req();
        r.setSummaryHtml("<p>Hi</p><script>alert(1)</script>");
        MailMessage m = assembler.assemble(r, FROM, TO);
        assertTrue(m.htmlBody().endsWith("<br><img src=\"cid:chart\" alt=\"chart\">"));
    }
}
