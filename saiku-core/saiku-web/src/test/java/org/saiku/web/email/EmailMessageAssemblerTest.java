package org.saiku.web.email;

import static org.junit.Assert.*;

import java.util.Base64;
import org.junit.Test;
import org.saiku.service.mail.MailMessage;

public class EmailMessageAssemblerTest {

    private static final String FROM = "saiku@example.com";
    private final EmailMessageAssembler assembler = new EmailMessageAssembler();

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static EmailSelfRequest req(String address) {
        EmailSelfRequest r = new EmailSelfRequest();
        r.setAddress(address);
        r.setSubject("Your analysis");
        r.setSummaryHtml("<p>Here is what changed.</p>");
        r.setChartPngBase64(b64(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}));
        r.setPdfBase64(b64("%PDF-1.4".getBytes()));
        return r;
    }

    @Test
    public void assembles_html_inlineChart_and_pdf() {
        MailMessage m = assembler.assemble(req("me@example.com"), FROM);
        assertEquals("me@example.com", m.to());
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

    @Test
    public void missingChart_isOmitted_pdfStillAttached() {
        EmailSelfRequest r = req("me@example.com");
        r.setChartPngBase64(null);
        MailMessage m = assembler.assemble(r, FROM);
        assertTrue(m.inlineImages().isEmpty());
        assertFalse(m.htmlBody().contains("cid:chart"));
        assertEquals(1, m.attachments().size());
    }

    @Test
    public void invalidAddress_isRejected() {
        try {
            assembler.assemble(req("not-an-email"), FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
        try {
            assembler.assemble(req(""), FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
        try {
            assembler.assemble(req(null), FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void crlfInAddress_cannotInjectHeaders() {
        try {
            assembler.assemble(req("me@example.com\r\nBcc: victim@example.com"), FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void crlfInSubject_isStripped() {
        EmailSelfRequest r = req("me@example.com");
        r.setSubject("Hello\r\nBcc: victim@example.com");
        MailMessage m = assembler.assemble(r, FROM);
        assertFalse(m.subject().contains("\r"));
        assertFalse(m.subject().contains("\n"));
    }

    @Test
    public void oversizeArtifact_isRejected() {
        EmailSelfRequest r = req("me@example.com");
        r.setPdfBase64(b64(new byte[16 * 1024 * 1024])); // 16 MB > 15 MB cap
        try {
            assembler.assemble(r, FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }

    @Test
    public void invalidBase64_isRejected() {
        EmailSelfRequest r = req("me@example.com");
        r.setPdfBase64("!!!not-base64!!!");
        try {
            assembler.assemble(r, FROM);
            fail("Should throw EmailRequestException");
        } catch (EmailRequestException e) {
            // expected
        }
    }
}
