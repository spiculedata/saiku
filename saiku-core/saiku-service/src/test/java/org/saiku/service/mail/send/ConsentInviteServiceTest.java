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
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.SuppressionStore;

/**
 * The consent-invite path: allowlist-gated, never-to-suppressed, consent-exempt bootstrap, behind the
 * flag, server-constant body carrying the confirm link.
 */
public class ConsentInviteServiceTest {

    private static final String FROM = "reports@example.com";

    private RecipientTrustStore trust;
    private SuppressionStore suppression;
    private RecipientConsentStore consent;
    private MailLinkBuilder links;

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
            return Files.createTempDirectory("saiku-invite-");
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
        links = new MailLinkBuilder("https://analytics.example.com");
    }

    private ConsentInviteService service(boolean flagOn) {
        return service(flagOn, links);
    }

    private ConsentInviteService service(boolean flagOn, MailLinkBuilder lb) {
        MailSendPolicy policy = new MailSendPolicy(k -> flagOn ? "true" : "false", k -> null);
        return new ConsentInviteService(policy, trust, suppression, consent, lb);
    }

    private void allowlist(String address) {
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add(address);
        trust.save(addrs, List.of());
    }

    // ================================================================ flag OFF

    @Test
    public void flagOff_doesNotSend() {
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        assertEquals(ConsentInviteService.Outcome.DISABLED, service(false).invite(sender, FROM, "a@example.com"));
        assertTrue(sender.sent.isEmpty());
    }

    // ================================================================ allowlist gate

    @Test
    public void notAllowlisted_isRefused_noSend() {
        CapturingSender sender = new CapturingSender();
        assertEquals(
                ConsentInviteService.Outcome.NOT_ALLOWLISTED,
                service(true).invite(sender, FROM, "stranger@example.com"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void allowlisted_sendsOneInvite() {
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        assertEquals(ConsentInviteService.Outcome.SENT, service(true).invite(sender, FROM, "a@example.com"));
        assertEquals(1, sender.sent.size());
        assertEquals("a@example.com", sender.sent.get(0).to());
        assertEquals(FROM, sender.sent.get(0).from());
    }

    // ================================================================ never to suppressed

    @Test
    public void suppressed_isNeverInvited_evenIfAllowlisted() {
        allowlist("a@example.com");
        suppression.suppress("a@example.com", "unsubscribe");
        CapturingSender sender = new CapturingSender();
        assertEquals(ConsentInviteService.Outcome.SUPPRESSED, service(true).invite(sender, FROM, "a@example.com"));
        assertTrue(sender.sent.isEmpty());
    }

    // ================================================================ consent-exempt bootstrap

    @Test
    public void invitesEvenWhenNotYetConfirmed_theBootstrap() {
        allowlist("a@example.com");
        // No prior consent record. The invite is the ONLY consent-exempt send.
        assertFalse(consent.isConfirmed("a@example.com"));
        CapturingSender sender = new CapturingSender();
        assertEquals(ConsentInviteService.Outcome.SENT, service(true).invite(sender, FROM, "a@example.com"));
        // Requesting consent recorded a PENDING entry, but the address is not yet confirmed.
        assertFalse(consent.isConfirmed("a@example.com"));
    }

    // ================================================================ server-constant content + link

    @Test
    public void body_isServerConstant_andCarriesConfirmLink() {
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        service(true).invite(sender, FROM, "a@example.com");
        MailMessage m = sender.sent.get(0);
        assertEquals(ConsentInviteService.INVITE_SUBJECT, m.subject());
        assertTrue("body must carry the confirm link", m.htmlBody().contains("/rest/saiku/mail/consent/confirm"));
        assertTrue(m.htmlBody().contains("t=")); // the raw token rides in the link
    }

    @Test
    public void linkUnconfigured_refusesRatherThanSendUselessInvite() {
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        ConsentInviteService svc = service(true, new MailLinkBuilder((String) null));
        assertEquals(ConsentInviteService.Outcome.LINK_UNCONFIGURED, svc.invite(sender, FROM, "a@example.com"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void invalidAddress_isRefused_asNotAllowlisted() {
        // The allowlist gate runs first and normalises identically to the store, so a malformed address
        // can never BE on the allowlist -> the pinned outcome is NOT_ALLOWLISTED (the invalid address is
        // rejected before consent is ever requested). Assert exactly that, and that nothing is sent.
        CapturingSender sender = new CapturingSender();
        assertEquals(ConsentInviteService.Outcome.NOT_ALLOWLISTED, service(true).invite(sender, FROM, "not-an-email"));
        assertTrue(sender.sent.isEmpty());
    }
}
