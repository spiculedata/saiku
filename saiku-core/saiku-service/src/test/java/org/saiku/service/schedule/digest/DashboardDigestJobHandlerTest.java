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
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.mail.send.MailSendPolicy;
import org.saiku.service.mail.send.MultiRecipientMailService;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.service.mail.trust.RecipientGate;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.SuppressionStore;
import org.saiku.service.mail.trust.UnsubscribeTokens;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.alert.MeasureValueReader;

/**
 * Delivery + off-request tests for {@link DashboardDigestJobHandler} (saiku#943).
 *
 * <p>Locks: flag OFF (default) never mails a non-self recipient (self-email fallback only); flag ON with
 * a vetted recipient routes through {@link MultiRecipientMailService} (individually addressed, gate
 * consulted); the measure read runs off-request without a scope error.
 */
public class DashboardDigestJobHandlerTest {

    private static final String FROM = "reports@example.com";
    private static final String SELF = "ops-inbox@example.com";
    private static final byte[] KEY = "test-install-key-material-32bytes!".getBytes();
    private static final String BASE = "https://analytics.example.com";

    private RecipientTrustStore trust;
    private SuppressionStore suppression;
    private RecipientConsentStore consent;
    private RecipientGate gate;
    private UnsubscribeTokens tokens;
    private MailLinkBuilder links;

    /** Captures every message the sender was asked to send. */
    private static final class CapturingSender implements MailSender {
        final List<MailMessage> sent = new ArrayList<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage m) throws MailException {
            sent.add(m);
        }
    }

    private static Path tempHome() {
        try {
            return Files.createTempDirectory("saiku-digest-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setUp() {
        Path home = tempHome();
        trust = new RecipientTrustStore(home);
        suppression = new SuppressionStore(home);
        consent = new RecipientConsentStore(home);
        gate = new RecipientGate(suppression, trust, consent);
        tokens = new UnsubscribeTokens(KEY);
        links = new MailLinkBuilder(BASE);
    }

    private void allowAndConfirm(String address) {
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add(address);
        trust.save(addrs, List.of());
        String token = consent.requestConsent(address);
        assertTrue(consent.confirm(address, token));
    }

    private MultiRecipientMailService multiService(boolean flagOn) {
        MailSendPolicy policy = new MailSendPolicy(k -> flagOn ? "true" : "false", k -> null);
        return new MultiRecipientMailService(policy, gate, tokens, links);
    }

    /** A self-address-configured mail config (host + from + selfTo all set). */
    private static MailConfig configWithSelf() {
        return new MailConfig("smtp.example.com", 587, "u", "p", FROM, true, false, SELF);
    }

    private static final MeasureValueReader READER = (AiCubeRef cube, String measure, List<AiFilterSelection> f) -> {
        if ("Unit Sales".equals(measure)) {
            return 1234.0;
        }
        return 56789.5;
    };

    private static ScheduledJobFile job(List<String> recipients) {
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("path", "shared/exec.saikudash");
        dash.put("title", "Executive Overview");
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("cube", "conn/cat/schema/Sales");
        m1.put("measure", "Unit Sales");
        m1.put("label", "Total Units");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dashboard", dash);
        payload.put("measures", List.of(m1));
        if (recipients != null) {
            payload.put("recipients", recipients);
        }
        ScheduledJobFile j = new ScheduledJobFile();
        j.setId("job-1");
        j.setType("DASHBOARD_DIGEST");
        j.setPayload(payload);
        return j;
    }

    // ---------------- flag OFF (default) — no non-self send, self-email fallback ----------------

    @Test
    public void flagOff_withRecipients_sendsOnlyToSelf() throws Exception {
        CapturingSender sender = new CapturingSender();
        allowAndConfirm("boss@example.com"); // even a fully-vetted recipient must not receive with flag off
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(READER, multiService(false), sender, configWithSelf(), links);

        handler.handle(job(List.of("boss@example.com")));

        assertEquals("exactly one message — the self fallback", 1, sender.sent.size());
        assertEquals("recipient must be selfTo only", SELF, sender.sent.get(0).to());
        assertFalse(
                "the vetted external recipient must NOT be mailed with the flag off",
                sender.sent.stream().anyMatch(m -> m.to().equalsIgnoreCase("boss@example.com")));
        // The self message carries the summary + deep link.
        assertTrue(sender.sent.get(0).htmlBody().contains("Total Units"));
        assertTrue(sender.sent.get(0).htmlBody().contains(BASE + "/ui/dashboards/shared/exec.saikudash"));
    }

    @Test
    public void noRecipients_sendsToSelf() throws Exception {
        CapturingSender sender = new CapturingSender();
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(READER, multiService(false), sender, configWithSelf(), links);

        handler.handle(job(null));

        assertEquals(1, sender.sent.size());
        assertEquals(SELF, sender.sent.get(0).to());
    }

    // ---------------- flag ON + vetted recipient — routes through the gate ----------------

    @Test
    public void flagOn_vettedRecipient_routesThroughGateIndividuallyAddressed() throws Exception {
        CapturingSender sender = new CapturingSender();
        allowAndConfirm("boss@example.com");
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(READER, multiService(true), sender, configWithSelf(), links);

        handler.handle(job(List.of("boss@example.com")));

        // Delivered to the vetted recipient (through the gate), NOT the self fallback.
        assertEquals(1, sender.sent.size());
        assertEquals("boss@example.com", sender.sent.get(0).to());
        // Individually addressed — the single To: is that recipient only.
        assertTrue(sender.sent.stream().allMatch(m -> "boss@example.com".equals(m.to())));
    }

    @Test
    public void flagOn_recipientNotConfirmed_gateDrops_fallsBackToSelf() throws Exception {
        CapturingSender sender = new CapturingSender();
        // Allowlisted but NOT consent-confirmed => the gate drops it.
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add("pending@example.com");
        trust.save(addrs, List.of());
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(READER, multiService(true), sender, configWithSelf(), links);

        handler.handle(job(List.of("pending@example.com")));

        // Gate cleared none => nothing sent to others => self-email fallback.
        assertEquals(1, sender.sent.size());
        assertEquals(SELF, sender.sent.get(0).to());
        assertFalse(sender.sent.stream().anyMatch(m -> m.to().equalsIgnoreCase("pending@example.com")));
    }

    // ---------------- off-request safety ----------------

    @Test
    public void handleRunsOffRequestThreadWithoutScopeError() throws Exception {
        final AtomicBoolean read = new AtomicBoolean(false);
        MeasureValueReader offRequestReader = (cube, measure, f) -> {
            read.set(true);
            return 1234.0;
        };
        CapturingSender sender = new CapturingSender();
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(offRequestReader, multiService(false), sender, configWithSelf(), links);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Void> task = () -> {
                handler.handle(job(null));
                return null;
            };
            pool.submit(task).get();
            assertTrue("the measure read must run off-request", read.get());
            assertEquals(1, sender.sent.size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- delivery failure surfaces (self fallback unconfigured) ----------------

    @Test(expected = DashboardDigestDeliveryException.class)
    public void selfFallbackUnconfigured_throws() throws Exception {
        CapturingSender sender = new CapturingSender();
        MailConfig noSelf = new MailConfig("smtp.example.com", 587, "u", "p", FROM, true, false, null);
        DashboardDigestJobHandler handler =
                new DashboardDigestJobHandler(READER, multiService(false), sender, noSelf, links);
        handler.handle(job(null));
    }
}
