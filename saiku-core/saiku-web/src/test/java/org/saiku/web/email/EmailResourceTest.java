/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SH-2 — per-user send rate-limit on {@code POST /saiku/api/email/self}, plus the
 * pre-existing auth/configured/send behaviour it must not disturb.
 */
public class EmailResourceTest {

    private static final String SELF_TO = "self@example.com";
    private static final MailConfig CONFIG =
            new MailConfig("smtp.example.com", 587, "user", "pass", "saiku@example.com", true, false, SELF_TO);
    private static final MailConfig CONFIG_NO_SELFTO =
            new MailConfig("smtp.example.com", 587, "user", "pass", "saiku@example.com", true, false, null);

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String principal) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "x", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private static EmailSelfRequest validBody() {
        EmailSelfRequest r = new EmailSelfRequest();
        r.setSubject("Your analysis");
        r.setSummaryHtml("<p>Here is what changed.</p>");
        r.setChartPngBase64(Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}));
        r.setPdfBase64(Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()));
        return r;
    }

    /** Counts sends; always "succeeds" so the resource's happy path is exercised. */
    private static final class CountingSender implements MailSender {
        final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage message) throws MailException {
            sendCount.incrementAndGet();
        }
    }

    private static EmailResource resource(MailSender sender) {
        EmailResource r = new EmailResource();
        r.setMailSender(sender);
        r.setMailConfig(CONFIG);
        return r;
    }

    // ---------- pre-existing behaviour (must stay green) ----------

    @Test
    public void anonymousCall_returns401_beforeTouchingMailOrLimiter() {
        SecurityContextHolder.clearContext();
        EmailResource r = resource(new CountingSender());
        Response resp = r.sendSelf(validBody());
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void unauthenticatedAnonymousToken_returns401() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        SecurityContextHolder.setContext(ctx);

        EmailResource r = resource(new CountingSender());
        Response resp = r.sendSelf(validBody());
        assertEquals(401, resp.getStatus());
    }

    @Test
    public void authenticatedCall_configuredSender_returns200_andSends() {
        login("alice");
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        Response resp = r.sendSelf(validBody());
        assertEquals(200, resp.getStatus());
        assertEquals(1, sender.sendCount.get());
        assertEquals("sent", ((Map<?, ?>) resp.getEntity()).get("status"));
    }

    /**
     * F1 (open-relay) fix: the recipient is the admin-configured {@code selfTo}, never a client
     * value. The response echoes the recipient actually used — it must equal {@code selfTo}.
     */
    @Test
    public void recipient_isServerConfiguredSelfTo_notAClientValue() {
        login("alice");
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        Response resp = r.sendSelf(validBody());
        assertEquals(200, resp.getStatus());
        assertEquals(SELF_TO, ((Map<?, ?>) resp.getEntity()).get("to"));
        assertEquals(1, sender.sendCount.get());
    }

    /** Fail closed: with no self-recipient configured, sendSelf returns 503 and never sends. */
    @Test
    public void selfRecipientNotConfigured_returns503_andDoesNotSend() {
        login("alice");
        CountingSender sender = new CountingSender();
        EmailResource r = new EmailResource();
        r.setMailSender(sender);
        r.setMailConfig(CONFIG_NO_SELFTO);
        Response resp = r.sendSelf(validBody());
        assertEquals(503, resp.getStatus());
        assertEquals(0, sender.sendCount.get());
    }

    @Test
    public void notConfigured_returns503() {
        login("alice");
        EmailResource r = new EmailResource();
        r.setMailSender(null);
        r.setMailConfig(CONFIG);
        Response resp = r.sendSelf(validBody());
        assertEquals(503, resp.getStatus());
    }

    // ---------- SH-2: rate limiting ----------

    @Test
    public void withinLimit_bothCallsSucceed() {
        login("alice");
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        r.setEmailRateLimiter(new AiRateLimiter(2, 60_000L));

        assertEquals(200, r.sendSelf(validBody()).getStatus());
        assertEquals(200, r.sendSelf(validBody()).getStatus());
        assertEquals(2, sender.sendCount.get());
    }

    @Test
    public void overLimit_returns429_andDoesNotSendAgain() {
        login("alice");
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        r.setEmailRateLimiter(new AiRateLimiter(1, 60_000L));

        assertEquals(200, r.sendSelf(validBody()).getStatus());
        Response second = r.sendSelf(validBody());
        assertEquals(429, second.getStatus());
        assertEquals(1, sender.sendCount.get());
        Object error = ((Map<?, ?>) second.getEntity()).get("error");
        assertNotNull(error);
        assertTrue(error.toString().contains("limit"));
    }

    @Test
    public void perUserBuckets_areIndependent() {
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        r.setEmailRateLimiter(new AiRateLimiter(1, 60_000L));

        login("alice");
        assertEquals(200, r.sendSelf(validBody()).getStatus());

        login("bob");
        assertEquals(200, r.sendSelf(validBody()).getStatus());

        assertEquals(2, sender.sendCount.get());
    }

    @Test
    public void rateLimitDoesNotFire_beforeAuthCheck() {
        SecurityContextHolder.clearContext();
        CountingSender sender = new CountingSender();
        EmailResource r = resource(sender);
        // Exhausted limiter — if the auth check didn't run first, an anonymous
        // caller would still get 429 instead of 401.
        r.setEmailRateLimiter(new AiRateLimiter(0, 60_000L));

        Response resp = r.sendSelf(validBody());
        assertEquals(401, resp.getStatus());
        assertEquals(0, sender.sendCount.get());
    }
}
