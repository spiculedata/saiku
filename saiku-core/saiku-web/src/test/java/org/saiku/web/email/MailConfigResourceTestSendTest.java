/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#943 P0-C — admin SMTP test-send endpoint ({@code POST /saiku/api/admin/mail-config/test}).
 *
 * <p>Locks the SEC-load-bearing anti-relay behaviour: anonymous -> 401, non-admin -> 403 (nothing
 * sent), fail-closed 503 when unconfigured / no {@code selfTo}, rate-limited -> 429, the recipient is
 * ALWAYS the server-owned {@code selfTo} (no client recipient possible — no request body/param), and
 * transport failures return a sanitized 502 that never leaks the SMTP host or exception detail.
 */
public class MailConfigResourceTestSendTest {

    private static final String SELF_TO = "admin-self@example.com";
    private static final String FROM = "reports@example.com";
    private static final String SMTP_HOST = "smtp.internal.example.com";

    /** A fully-configured effective config (host + from + selfTo). */
    private static final MailConfig CONFIG = new MailConfig(SMTP_HOST, 587, "user", "pass", FROM, true, false, SELF_TO);
    /** Host + from set but no selfTo — fail-closed on the recipient. */
    private static final MailConfig CONFIG_NO_SELFTO =
            new MailConfig(SMTP_HOST, 587, "user", "pass", FROM, true, false, null);
    /** Nothing configured. */
    private static final MailConfig CONFIG_EMPTY = new MailConfig(null, 587, null, null, null, true, false, null);

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------- auth seeding

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

    // -------------------------------------------------------------------- capturing sender

    /** Records the message it was asked to send; reports configured. */
    private static final class CapturingSender implements MailSender {
        final AtomicReference<MailMessage> sent = new AtomicReference<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage message) throws MailException {
            sent.set(message);
        }
    }

    /** Throws a transport failure whose message embeds the SMTP host, to prove it isn't leaked. */
    private static final class FailingSender implements MailSender {
        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage message) throws MailException {
            throw new MailException("SMTP 535 auth failed talking to " + SMTP_HOST);
        }
    }

    // -------------------------------------------------------------------- resource wiring

    /**
     * Build a real {@link MailConfigResolver} whose ops-managed (env-wins) path yields {@code cfg} as
     * the effective config. When {@code cfg} has a host or from, env supplies host/from/etc, so
     * {@code effective()} returns exactly those env values — no subclass/mock needed (the resolver is
     * final), the real merge logic is exercised.
     */
    private static MailConfigResolver resolverFor(MailConfig cfg) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        if (cfg.host() != null) m.put("SAIKU_MAIL_SMTP_HOST", cfg.host());
        if (cfg.from() != null) m.put("SAIKU_MAIL_FROM", cfg.from());
        if (cfg.username() != null) m.put("SAIKU_MAIL_SMTP_USERNAME", cfg.username());
        if (cfg.password() != null) m.put("SAIKU_MAIL_SMTP_PASSWORD", cfg.password());
        if (cfg.selfTo() != null) m.put("SAIKU_MAIL_SELF_TO", cfg.selfTo());
        m.put("SAIKU_MAIL_SMTP_PORT", Integer.toString(cfg.port()));
        m.put("SAIKU_MAIL_SMTP_STARTTLS", Boolean.toString(cfg.startTls()));
        m.put("SAIKU_MAIL_SMTP_SSL", Boolean.toString(cfg.ssl()));
        return new MailConfigResolver(m::get, k -> null, new MailConfigStore(tempHome()));
    }

    /** Build a resource whose resolver returns the given effective config, using the given sender. */
    private static MailConfigResource resource(MailConfig effective, MailSender sender, boolean admin) {
        MailConfigResource r = new MailConfigResource();
        r.setUserService(new StubUserService(admin));
        r.setMailConfigResolver(resolverFor(effective));
        r.setSenderFactory(cfg -> sender);
        return r;
    }

    private static MailConfigResource resource(MailConfig effective, MailSender sender) {
        return resource(effective, sender, true);
    }

    // ================================================================ tests

    @Test
    public void anonymous_returns401_andDoesNotSend() {
        loginAnonymous();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG, sender).sendTest();
        assertEquals(401, resp.getStatus());
        assertNull("must not send for an anonymous caller", sender.sent.get());
    }

    @Test
    public void noAuthContext_returns401() {
        SecurityContextHolder.clearContext();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG, sender).sendTest();
        assertEquals(401, resp.getStatus());
        assertNull(sender.sent.get());
    }

    @Test
    public void authenticatedNonAdmin_returns403_andDoesNotSend() {
        login("bob"); // authenticated, but the stub UserService says not-admin
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG, sender, false).sendTest();
        assertEquals(403, resp.getStatus());
        assertNull("must not send for a non-admin", sender.sent.get());
    }

    @Test
    public void notConfigured_returns503_failClosed() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG_EMPTY, sender).sendTest();
        assertEquals(503, resp.getStatus());
        assertNull(sender.sent.get());
    }

    @Test
    public void noSelfTo_returns503_failClosed() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG_NO_SELFTO, sender).sendTest();
        assertEquals(503, resp.getStatus());
        assertNull("no recipient configured -> must not send", sender.sent.get());
    }

    @Test
    public void success_sendsToSelfTo_only() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG, sender).sendTest();
        assertEquals(200, resp.getStatus());
        assertEquals("sent", ((Map<?, ?>) resp.getEntity()).get("status"));
        assertEquals(SELF_TO, ((Map<?, ?>) resp.getEntity()).get("to"));

        MailMessage msg = sender.sent.get();
        assertNotNull("the test message must have been sent", msg);
        // The recipient is ALWAYS the server-owned selfTo — never anything else.
        assertEquals(SELF_TO, msg.to());
        assertEquals(FROM, msg.from());
    }

    /**
     * Anti-relay by construction: {@code sendTest} takes NO parameter, so there is no way for a caller
     * to supply a recipient. The recipient can only ever be the server's {@code selfTo}.
     */
    @Test
    public void sendTest_takesNoRequestBody_soNoRecipientCanBeInjected() throws Exception {
        Method m = MailConfigResource.class.getMethod("sendTest");
        assertEquals("sendTest must accept no arguments (no client-supplied recipient)", 0, m.getParameterCount());
    }

    @Test
    public void rateLimited_returns429_andDoesNotSendAgain() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        MailConfigResource r = resource(CONFIG, sender);
        r.setTestSendRateLimiter(new AiRateLimiter(1, 60_000L));

        assertEquals(200, r.sendTest().getStatus());
        Response second = r.sendTest();
        assertEquals(429, second.getStatus());
        // The second call must not have reached the sender (still the first message).
        assertEquals(SELF_TO, sender.sent.get().to());
        Object error = ((Map<?, ?>) second.getEntity()).get("error");
        assertNotNull(error);
        assertTrue(error.toString().contains("limit"));
    }

    @Test
    public void rateLimitDoesNotFire_beforeAuthCheck() {
        loginAnonymous();
        CapturingSender sender = new CapturingSender();
        MailConfigResource r = resource(CONFIG, sender);
        // Exhausted limiter — an anon caller must still get 401 (auth checked first), never 429.
        r.setTestSendRateLimiter(new AiRateLimiter(0, 60_000L));
        Response resp = r.sendTest();
        assertEquals(401, resp.getStatus());
        assertNull(sender.sent.get());
    }

    @Test
    public void transportError_returnsSanitized502_noSmtpInternalsLeaked() {
        loginAdmin();
        Response resp = resource(CONFIG, new FailingSender()).sendTest();
        assertEquals(502, resp.getStatus());
        String body = resp.getEntity().toString();
        assertEquals("send failed", ((Map<?, ?>) resp.getEntity()).get("error"));
        // The SMTP host / exception detail must NEVER reach the client.
        assertFalse("must not leak the SMTP host", body.contains(SMTP_HOST));
        assertFalse("must not leak SMTP status codes / auth detail", body.contains("535"));
    }

    /**
     * Env-wins: the sender uses whatever {@link MailConfigResolver#effective()} returns. Here env
     * supplies host+from+selfTo (ops-managed), so the effective config — and thus the recipient — is
     * the env {@code selfTo}, proving the endpoint sends with the real resolved config.
     */
    @Test
    public void usesEffectiveConfig_fromResolver() {
        loginAdmin();
        CapturingSender sender = new CapturingSender();
        Response resp = resource(CONFIG, sender).sendTest();
        assertEquals(200, resp.getStatus());
        assertEquals(SELF_TO, sender.sent.get().to());
        assertEquals(FROM, sender.sent.get().from());
    }

    // -------------------------------------------------------------------- stubs

    private static java.nio.file.Path tempHome() {
        try {
            return java.nio.file.Files.createTempDirectory("saiku-mailcfg-p0c-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
