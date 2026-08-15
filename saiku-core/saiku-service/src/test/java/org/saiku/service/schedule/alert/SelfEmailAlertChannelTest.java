/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import static org.junit.Assert.*;

import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;

/**
 * {@link SelfEmailAlertChannel} sends ONLY to the server's configured self-address (saiku#1098) —
 * never a client / event-supplied recipient — and fails closed when mail is unconfigured.
 */
public class SelfEmailAlertChannelTest {

    private static final class CapturingSender implements MailSender {
        boolean configured = true;
        MailMessage sent;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void send(MailMessage message) throws MailException {
            this.sent = message;
        }
    }

    private static MailConfig config(String from, String selfTo) {
        return new MailConfig("smtp.example.com", 587, "u", "p", from, true, false, selfTo);
    }

    private static AlertEvent event() {
        return new AlertEvent(
                "JOB123",
                "conn/cat/schema/Sales",
                "Unit Sales",
                AlertComparison.GT,
                150.0,
                120.0,
                100.0,
                1700000000000L);
    }

    private static AlertChannelConfig emailChannel() {
        return AlertChannelConfig.fromPayload(java.util.Map.of("type", "EMAIL_SELF"));
    }

    @Test
    public void sendsToSelfAddressOnly() throws Exception {
        CapturingSender sender = new CapturingSender();
        SelfEmailAlertChannel channel = new SelfEmailAlertChannel(sender, config("alerts@saiku", "admin@saiku"));

        channel.deliver(event(), emailChannel());

        assertNotNull(sender.sent);
        assertEquals("admin@saiku", sender.sent.to());
        assertEquals("alerts@saiku", sender.sent.from());
        assertTrue(sender.sent.subject().contains("Unit Sales"));
        assertTrue(sender.sent.htmlBody().contains("Unit Sales"));
        assertTrue(sender.sent.htmlBody().contains("150.0"));
        // No unsubscribe header, no send-to-others machinery.
        assertNull(sender.sent.listUnsubscribe());
    }

    @Test
    public void htmlEscapesMeasureAndCubeNames() throws Exception {
        CapturingSender sender = new CapturingSender();
        SelfEmailAlertChannel channel = new SelfEmailAlertChannel(sender, config("alerts@saiku", "admin@saiku"));
        AlertEvent xss = new AlertEvent(
                "JOB123",
                "conn/cat/schema/<script>alert(1)</script>",
                "<script>alert('x')</script>",
                AlertComparison.GT,
                150.0,
                null,
                100.0,
                1700000000000L);

        channel.deliver(xss, emailChannel());

        String body = sender.sent.htmlBody();
        // The raw script tag must NOT survive into the HTML body; it must be escaped.
        assertFalse("raw <script> must not survive", body.contains("<script>"));
        assertTrue("angle brackets must be escaped", body.contains("&lt;script&gt;"));
    }

    @Test
    public void failsClosedWhenUnconfigured() {
        CapturingSender sender = new CapturingSender();
        sender.configured = false;
        SelfEmailAlertChannel channel = new SelfEmailAlertChannel(sender, config("alerts@saiku", "admin@saiku"));
        try {
            channel.deliver(event(), emailChannel());
            fail("expected fail-closed");
        } catch (Exception e) {
            assertTrue(e instanceof AlertDeliveryException);
        }
        assertNull(sender.sent);
    }

    @Test
    public void failsClosedWhenNoSelfAddress() {
        CapturingSender sender = new CapturingSender();
        SelfEmailAlertChannel channel = new SelfEmailAlertChannel(sender, config("alerts@saiku", null));
        try {
            channel.deliver(event(), emailChannel());
            fail("expected fail-closed");
        } catch (Exception e) {
            assertTrue(e instanceof AlertDeliveryException);
        }
        assertNull(sender.sent);
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongChannelTypeRejected() throws Exception {
        CapturingSender sender = new CapturingSender();
        SelfEmailAlertChannel channel = new SelfEmailAlertChannel(sender, config("alerts@saiku", "admin@saiku"));
        channel.deliver(
                event(),
                AlertChannelConfig.fromPayload(java.util.Map.of("type", "WEBHOOK", "url", "https://93.184.216.34/x")));
    }
}
