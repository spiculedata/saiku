package org.saiku.service.mail;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoopMailSenderTest {

    @Test
    void isNotConfigured() {
        assertFalse(new NoopMailSender().isConfigured());
    }

    @Test
    void send_completesWithoutThrowing_whenUnconfigured() {
        MailMessage m = new MailMessage("to@x.com", "from@x.com", "s", "<p>b</p>", List.of(), List.of());
        assertDoesNotThrow(() -> new NoopMailSender().send(m));
    }
}
