/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.send.ConsentInviteService;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.mail.send.MailSendPolicy;
import org.saiku.service.mail.send.MultiRecipientMailService;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.service.mail.trust.RecipientGate;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.SuppressionStore;
import org.saiku.service.mail.trust.UnsubscribeTokens;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The admin send-gate surface — the highest-scrutiny REST resource in the track.
 *
 * <p>Locks: flag OFF -> 403 on both send endpoints (nothing sent); auth (401 anon / 403 non-admin);
 * flag ON only mails cleared recipients; invite only to allowlisted, never to suppressed; individually
 * addressed (no recipient-list leak); response never echoes an address; rate limits.
 */
public class MailSendResourceTest {

    private static final String FROM = "reports@example.com";
    private static final String SELF_TO = "admin-self@example.com";
    private static final String SMTP_HOST = "smtp.internal.example.com";
    private static final byte[] KEY = "test-install-key-material-32bytes!".getBytes();

    private Path home;
    private RecipientTrustStore trust;
    private SuppressionStore suppression;
    private RecipientConsentStore consent;
    private RecipientGate gate;
    private UnsubscribeTokens tokens;
    private MailLinkBuilder links;

    private static final MailConfig CONFIG = new MailConfig(SMTP_HOST, 587, "u", "p", FROM, true, false, SELF_TO);

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

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailsendres-");
        trust = new RecipientTrustStore(home);
        suppression = new SuppressionStore(home);
        consent = new RecipientConsentStore(home);
        gate = new RecipientGate(suppression, trust, consent);
        tokens = new UnsubscribeTokens(KEY);
        links = new MailLinkBuilder("https://analytics.example.com");
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- auth seeding ----

    private static void loginAdmin() {
        login("admin");
    }

    private static void login(String principal) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "x", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private static void loginAnonymous() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        SecurityContextHolder.setContext(ctx);
    }

    // ---- resolver / resource wiring ----

    private static MailConfigResolver resolverFor(MailConfig cfg, Path home) {
        Map<String, String> m = new HashMap<>();
        if (cfg.host() != null) m.put("SAIKU_MAIL_SMTP_HOST", cfg.host());
        if (cfg.from() != null) m.put("SAIKU_MAIL_FROM", cfg.from());
        if (cfg.selfTo() != null) m.put("SAIKU_MAIL_SELF_TO", cfg.selfTo());
        m.put("SAIKU_MAIL_SMTP_PORT", Integer.toString(cfg.port()));
        return new MailConfigResolver(m::get, k -> null, new MailConfigStore(home));
    }

    private MailSendResource resource(boolean flagOn, boolean admin, MailConfig cfg, MailSender sender) {
        MailSendPolicy policy = new MailSendPolicy(k -> flagOn ? "true" : "false", k -> null);
        MultiRecipientMailService multi = new MultiRecipientMailService(policy, gate, tokens, links);
        ConsentInviteService invite = new ConsentInviteService(policy, trust, suppression, consent, links);
        MailSendResource r = new MailSendResource();
        r.setMultiRecipientMailService(multi);
        r.setConsentInviteService(invite);
        r.setMailConfigResolver(resolverFor(cfg, home));
        r.setUserService(new StubUserService(admin));
        r.setSenderFactory(c -> sender);
        return r;
    }

    private void allowAndConfirm(String address) {
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add(address);
        trust.save(addrs, List.of());
        String t = consent.requestConsent(address);
        assertTrue(consent.confirm(address, t));
    }

    private void allowlist(String address) {
        List<String> addrs = new ArrayList<>(trust.readView().addresses());
        addrs.add(address);
        trust.save(addrs, List.of());
    }

    private static MailSendRequest sendReq(String... recipients) {
        MailSendRequest req = new MailSendRequest();
        req.setRecipients(new ArrayList<>(List.of(recipients)));
        EmailSelfRequest msg = new EmailSelfRequest();
        msg.setSubject("Weekly report");
        msg.setSummaryHtml("<p>Numbers</p>");
        req.setMessage(msg);
        return req;
    }

    private static MailInviteRequest inviteReq(String address) {
        MailInviteRequest req = new MailInviteRequest();
        req.setAddress(address);
        return req;
    }

    // ================================================================ flag OFF (default)

    @Test
    public void flagOff_send_returns403_nothingSent() {
        loginAdmin();
        allowAndConfirm("a@example.com");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(false, true, CONFIG, sender).send(sendReq("a@example.com"));
        assertEquals(403, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void flagOff_invite_returns403_nothingSent() {
        loginAdmin();
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(false, true, CONFIG, sender).invite(inviteReq("a@example.com"));
        assertEquals(403, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void flagOff_status_reportsDisabled() {
        loginAdmin();
        Response resp = resource(false, true, CONFIG, new CapturingSender()).status();
        assertEquals(200, resp.getStatus());
        assertEquals(Boolean.FALSE, ((Map<?, ?>) resp.getEntity()).get("sendToOthersEnabled"));
    }

    // ================================================================ auth

    @Test
    public void anonymous_send_returns401() {
        loginAnonymous();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).send(sendReq("a@example.com"));
        assertEquals(401, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void nonAdmin_send_returns403() {
        login("bob");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, false, CONFIG, sender).send(sendReq("a@example.com"));
        assertEquals(403, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void anonymous_invite_returns401() {
        loginAnonymous();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).invite(inviteReq("a@example.com"));
        assertEquals(401, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    // ================================================================ flag ON — multi-send

    @Test
    public void flagOn_send_mailsOnlyClearedRecipients() {
        loginAdmin();
        allowAndConfirm("ok@example.com");
        // stranger is not allowlisted/confirmed; banned is suppressed.
        allowAndConfirm("banned@example.com");
        suppression.suppress("banned@example.com", "unsubscribe");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender)
                .send(sendReq("ok@example.com", "stranger@example.com", "banned@example.com"));
        assertEquals(200, resp.getStatus());
        assertEquals(1, sender.sent.size());
        assertEquals("ok@example.com", sender.sent.get(0).to());
        Map<?, ?> body = (Map<?, ?>) resp.getEntity();
        assertEquals(1, body.get("sent"));
        assertEquals(2, body.get("dropped"));
    }

    @Test
    public void flagOn_send_isIndividuallyAddressed_noRecipientLeak() {
        loginAdmin();
        allowAndConfirm("alice@example.com");
        allowAndConfirm("bob@example.com");
        CapturingSender sender = new CapturingSender();
        resource(true, true, CONFIG, sender).send(sendReq("alice@example.com", "bob@example.com"));
        assertEquals(2, sender.sent.size());
        for (MailMessage m : sender.sent) {
            String other = m.to().equals("alice@example.com") ? "bob@example.com" : "alice@example.com";
            String blob = m.to() + "|" + m.subject() + "|" + m.htmlBody() + "|" + m.listUnsubscribe();
            assertFalse("no other recipient address may appear", blob.contains(other));
            org.junit.Assert.assertNotNull("per-recipient List-Unsubscribe", m.listUnsubscribe());
        }
    }

    @Test
    public void send_responseNeverEchoesAnAddress() {
        loginAdmin();
        allowAndConfirm("ok@example.com");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).send(sendReq("ok@example.com", "stranger@example.com"));
        String body = resp.getEntity().toString();
        assertFalse(body.contains("ok@example.com"));
        assertFalse(body.contains("stranger@example.com"));
    }

    @Test
    public void send_emptyRecipients_returns400() {
        loginAdmin();
        Response resp = resource(true, true, CONFIG, new CapturingSender()).send(sendReq());
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void send_rateLimited_returns429() {
        loginAdmin();
        allowAndConfirm("a@example.com");
        MailSendResource r = resource(true, true, CONFIG, new CapturingSender());
        r.setSendRateLimiter(new AiRateLimiter(1, 60_000L));
        assertEquals(200, r.send(sendReq("a@example.com")).getStatus());
        assertEquals(429, r.send(sendReq("a@example.com")).getStatus());
    }

    // ================================================================ flag ON — invite

    @Test
    public void invite_toAllowlisted_sendsOne() {
        loginAdmin();
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).invite(inviteReq("a@example.com"));
        assertEquals(200, resp.getStatus());
        assertEquals(1, sender.sent.size());
        assertEquals("a@example.com", sender.sent.get(0).to());
    }

    @Test
    public void invite_toNonAllowlisted_returns403_noSend() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).invite(inviteReq("stranger@example.com"));
        assertEquals(403, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void invite_toSuppressed_returns403_noSend() {
        loginAdmin();
        allowlist("a@example.com");
        suppression.suppress("a@example.com", "unsubscribe");
        CapturingSender sender = new CapturingSender();
        Response resp = resource(true, true, CONFIG, sender).invite(inviteReq("a@example.com"));
        assertEquals(403, resp.getStatus());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void invite_bodyIsServerConstant_carriesConfirmLink() {
        loginAdmin();
        allowlist("a@example.com");
        CapturingSender sender = new CapturingSender();
        resource(true, true, CONFIG, sender).invite(inviteReq("a@example.com"));
        MailMessage m = sender.sent.get(0);
        assertEquals(ConsentInviteService.INVITE_SUBJECT, m.subject());
        assertTrue(m.htmlBody().contains("/rest/saiku/mail/consent/confirm"));
    }

    @Test
    public void invite_rateLimited_returns429() {
        loginAdmin();
        allowlist("a@example.com");
        allowlist("b@example.com");
        MailSendResource r = resource(true, true, CONFIG, new CapturingSender());
        r.setInviteRateLimiter(new AiRateLimiter(1, 60_000L));
        assertEquals(200, r.invite(inviteReq("a@example.com")).getStatus());
        assertEquals(429, r.invite(inviteReq("b@example.com")).getStatus());
    }

    @Test
    public void invite_missingAddress_returns400() {
        loginAdmin();
        Response resp = resource(true, true, CONFIG, new CapturingSender()).invite(new MailInviteRequest());
        assertEquals(400, resp.getStatus());
    }

    // ---- stub ----

    private static final class StubUserService extends UserService {
        private final boolean admin;

        StubUserService(boolean admin) {
            this.admin = admin;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public String getActiveUsername() {
            return "admin";
        }
    }
}
