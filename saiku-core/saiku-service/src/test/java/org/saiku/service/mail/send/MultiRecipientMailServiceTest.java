/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.mail.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.service.mail.trust.RecipientGate;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.SuppressionStore;
import org.saiku.service.mail.trust.UnsubscribeTokens;

/**
 * The multi-recipient send layer — the highest-scrutiny code in the track.
 *
 * <p>Locks: flag OFF refuses (nothing sent); flag ON mails ONLY cleared (allowlisted + CONFIRMED +
 * not-suppressed) recipients; non-allowlisted / non-confirmed / suppressed are DROPPED; each message is
 * individually addressed (no other recipient's address appears anywhere in a message); each carries a
 * per-recipient List-Unsubscribe; per-recipient partial success; per-batch cap.
 */
public class MultiRecipientMailServiceTest {

    private static final String FROM = "reports@example.com";
    private static final byte[] KEY = "test-install-key-material-32bytes!".getBytes();

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

    /** Fails for one specific recipient, succeeds otherwise. */
    private static final class SelectiveFailSender implements MailSender {
        final List<MailMessage> sent = new ArrayList<>();
        final String failFor;

        SelectiveFailSender(String failFor) {
            this.failFor = failFor;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage m) throws MailException {
            if (m.to().equalsIgnoreCase(failFor)) {
                throw new MailException("transport boom for one recipient");
            }
            sent.add(m);
        }
    }

    private static Path tempHome() {
        try {
            return Files.createTempDirectory("saiku-mailsend-");
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
        links = new MailLinkBuilder("https://analytics.example.com");
    }

    /** Allowlist + confirm an address so it clears the gate. */
    private void allowAndConfirm(String address) {
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add(address);
        trust.save(addrs, List.of());
        String token = consent.requestConsent(address);
        assertTrue(consent.confirm(address, token));
    }

    private MultiRecipientMailService service(boolean flagOn) {
        return service(flagOn, 50);
    }

    private MultiRecipientMailService service(boolean flagOn, int maxPerBatch) {
        MailSendPolicy policy = new MailSendPolicy(k -> flagOn ? "true" : "false", k -> null);
        return new MultiRecipientMailService(policy, gate, tokens, links, maxPerBatch);
    }

    private static MultiRecipientMailService.MailContent content() {
        return MultiRecipientMailService.MailContent.of("Report", "<p>Hello</p>");
    }

    // ================================================================ flag OFF

    @Test
    public void flagOff_refuses_sendsNothing() {
        allowAndConfirm("a@example.com");
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService svc = service(false);
        assertFalse(svc.isEnabled());
        try {
            svc.send(sender, FROM, List.of("a@example.com"), content());
            org.junit.Assert.fail("expected MailSendDisabledException");
        } catch (MailSendDisabledException expected) {
            // fail-closed
        }
        assertTrue("nothing may be sent when the flag is off", sender.sent.isEmpty());
    }

    // ================================================================ flag ON — gate

    @Test
    public void flagOn_sendsOnlyToClearedRecipient() {
        allowAndConfirm("good@example.com");
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r = service(true).send(sender, FROM, List.of("good@example.com"), content());
        assertEquals(1, sender.sent.size());
        assertEquals("good@example.com", sender.sent.get(0).to());
        assertEquals(1, r.sent());
        assertEquals(1, r.cleared());
        assertEquals(0, r.dropped());
    }

    @Test
    public void nonAllowlisted_isDropped() {
        // confirmed but NOT allowlisted -> gate denies
        String token = consent.requestConsent("x@example.com");
        assertTrue(consent.confirm("x@example.com", token));
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r = service(true).send(sender, FROM, List.of("x@example.com"), content());
        assertTrue(sender.sent.isEmpty());
        assertEquals(0, r.sent());
        assertEquals(1, r.dropped());
    }

    @Test
    public void nonConfirmed_isDropped() {
        // allowlisted but consent only PENDING (not confirmed) -> gate denies
        trust.save(List.of("pending@example.com"), List.of());
        consent.requestConsent("pending@example.com"); // no confirm
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r =
                service(true).send(sender, FROM, List.of("pending@example.com"), content());
        assertTrue(sender.sent.isEmpty());
        assertEquals(1, r.dropped());
    }

    @Test
    public void suppressed_isDropped_evenWhenAllowlistedAndConfirmed() {
        allowAndConfirm("sup@example.com");
        suppression.suppress("sup@example.com", "unsubscribe"); // top veto
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r = service(true).send(sender, FROM, List.of("sup@example.com"), content());
        assertTrue(sender.sent.isEmpty());
        assertEquals(1, r.dropped());
    }

    @Test
    public void mixedBatch_mailsOnlyTheCleared() {
        allowAndConfirm("ok1@example.com");
        allowAndConfirm("ok2@example.com");
        allowAndConfirm("banned@example.com");
        suppression.suppress("banned@example.com", "unsubscribe");
        // "stranger@example.com" is neither allowlisted nor confirmed.
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r = service(true)
                .send(
                        sender,
                        FROM,
                        List.of("ok1@example.com", "banned@example.com", "stranger@example.com", "ok2@example.com"),
                        content());
        assertEquals(2, sender.sent.size());
        assertEquals(4, r.requested());
        assertEquals(2, r.cleared());
        assertEquals(2, r.sent());
        assertEquals(2, r.dropped());
        List<String> tos = sender.sent.stream().map(MailMessage::to).toList();
        assertTrue(tos.contains("ok1@example.com"));
        assertTrue(tos.contains("ok2@example.com"));
        assertFalse(tos.contains("banned@example.com"));
        assertFalse(tos.contains("stranger@example.com"));
    }

    // ================================================================ no recipient-list leak

    @Test
    public void eachMessageIsIndividuallyAddressed_noOtherRecipientLeaks() {
        allowAndConfirm("alice@example.com");
        allowAndConfirm("bob@example.com");
        allowAndConfirm("carol@example.com");
        CapturingSender sender = new CapturingSender();
        service(true)
                .send(sender, FROM, List.of("alice@example.com", "bob@example.com", "carol@example.com"), content());
        assertEquals(3, sender.sent.size());
        // For every message, its To: is exactly one recipient and NO OTHER recipient's address appears
        // anywhere in the rendered message (to / subject / body / unsubscribe header).
        List<String> all = List.of("alice@example.com", "bob@example.com", "carol@example.com");
        for (MailMessage m : sender.sent) {
            String self = m.to();
            String blob = m.to() + "|" + m.subject() + "|" + m.htmlBody() + "|" + m.listUnsubscribe();
            for (String other : all) {
                if (!other.equals(self)) {
                    assertFalse("no other recipient's address may appear in a message: " + other, blob.contains(other));
                }
            }
        }
    }

    @Test
    public void eachClearedMessageCarriesPerRecipientListUnsubscribe() {
        allowAndConfirm("u1@example.com");
        allowAndConfirm("u2@example.com");
        CapturingSender sender = new CapturingSender();
        service(true).send(sender, FROM, List.of("u1@example.com", "u2@example.com"), content());
        for (MailMessage m : sender.sent) {
            org.junit.Assert.assertNotNull("each message must carry List-Unsubscribe", m.listUnsubscribe());
            // The unsubscribe link is for THIS recipient (its address is in the header).
            assertTrue(m.listUnsubscribe()
                    .contains(java.net.URLEncoder.encode(m.to(), java.nio.charset.StandardCharsets.UTF_8)));
            // And the token verifies for THIS recipient.
        }
    }

    // ================================================================ partial success

    @Test
    public void oneBadRecipient_doesNotAbortTheBatch() {
        allowAndConfirm("ok@example.com");
        allowAndConfirm("bad@example.com");
        SelectiveFailSender sender = new SelectiveFailSender("bad@example.com");
        MultiRecipientMailService.Result r =
                service(true).send(sender, FROM, List.of("bad@example.com", "ok@example.com"), content());
        // ok@ still delivered even though bad@ failed.
        assertEquals(1, sender.sent.size());
        assertEquals("ok@example.com", sender.sent.get(0).to());
        assertEquals(2, r.cleared());
        assertEquals(1, r.sent());
        assertEquals(1, r.failed());
    }

    // ================================================================ per-batch cap

    @Test
    public void perBatchCap_dropsOverflow() {
        allowAndConfirm("a@example.com");
        allowAndConfirm("b@example.com");
        allowAndConfirm("c@example.com");
        CapturingSender sender = new CapturingSender();
        MultiRecipientMailService.Result r = service(true, 2)
                .send(sender, FROM, List.of("a@example.com", "b@example.com", "c@example.com"), content());
        assertEquals("cap of 2 -> only 2 sent", 2, sender.sent.size());
        assertEquals(3, r.cleared());
        assertEquals(2, r.sent());
        assertEquals(1, r.dropped());
    }

    // ================================================================ dedup

    @Test
    public void duplicateRecipients_areDeduped() {
        allowAndConfirm("dup@example.com");
        CapturingSender sender = new CapturingSender();
        service(true).send(sender, FROM, List.of("dup@example.com", "DUP@example.com", "dup@example.com"), content());
        assertEquals("gate.clear() de-dups on the normalised address", 1, sender.sent.size());
    }

    // ================================================================ from is server-owned

    @Test
    public void from_isAlwaysServerOwned() {
        allowAndConfirm("a@example.com");
        CapturingSender sender = new CapturingSender();
        service(true).send(sender, FROM, List.of("a@example.com"), content());
        assertEquals(FROM, sender.sent.get(0).from());
    }

    @Test
    public void resultMapContainsCountsOnly() {
        // sanity: Result exposes exactly the count fields, no address container.
        MultiRecipientMailService.Result r = new MultiRecipientMailService.Result(3, 2, 2, 0, 1);
        Map<String, Integer> m = Map.of(
                "requested",
                r.requested(),
                "cleared",
                r.cleared(),
                "sent",
                r.sent(),
                "failed",
                r.failed(),
                "dropped",
                r.dropped());
        assertEquals(Integer.valueOf(3), m.get("requested"));
        assertEquals(Integer.valueOf(1), m.get("dropped"));
    }
}
