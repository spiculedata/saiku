package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailMessageTest {

    @Test
    void nullLists_becomeEmpty_andListsAreDefensivelyCopied() {
        MailMessage m = new MailMessage("to@x.com", "from@x.com", "Subj", "<p>hi</p>", null, null);
        assertNotNull(m.inlineImages());
        assertNotNull(m.attachments());
        assertTrue(m.inlineImages().isEmpty());
        assertTrue(m.attachments().isEmpty());
    }

    @Test
    void requiredFields_areValidated() {
        assertThrows(
                NullPointerException.class, () -> new MailMessage(null, "from@x.com", "s", "b", List.of(), List.of()));
        assertThrows(
                NullPointerException.class, () -> new MailMessage("to@x.com", null, "s", "b", List.of(), List.of()));
    }

    @Test
    void carriesInlineImageAndAttachment() {
        MailMessage m = new MailMessage(
                "to@x.com",
                "from@x.com",
                "s",
                "<img src=\"cid:chart\">",
                List.of(new InlineImage("chart", "image/png", new byte[] {1, 2})),
                List.of(new Attachment("a.pdf", "application/pdf", new byte[] {3, 4})));
        assertEquals("chart", m.inlineImages().get(0).contentId());
        assertEquals("a.pdf", m.attachments().get(0).filename());
    }
}
