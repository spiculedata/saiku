package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;

public class EmailResourceHealthTest {

    private static MailSender sender(final boolean configured) {
        return new MailSender() {
            public boolean isConfigured() {
                return configured;
            }

            public void send(MailMessage m) {}
        };
    }

    @Test
    public void health_reportsConfiguredTrue_whenSenderConfigured() {
        EmailResource r = new EmailResource();
        r.setMailSender(sender(true));
        Response resp = r.health();
        assertEquals(200, resp.getStatus());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) resp.getEntity()).get("configured"));
    }

    @Test
    public void health_reportsConfiguredFalse_whenNoop_orNull() {
        EmailResource r = new EmailResource();
        r.setMailSender(sender(false));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) r.health().getEntity()).get("configured"));
        EmailResource r2 = new EmailResource(); // mailSender left null
        assertEquals(Boolean.FALSE, ((Map<?, ?>) r2.health().getEntity()).get("configured"));
    }
}
