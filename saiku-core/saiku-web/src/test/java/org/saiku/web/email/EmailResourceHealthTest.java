/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

public class EmailResourceHealthTest {

    private static MailSender sender(final boolean configured) {
        return new MailSender() {
            public boolean isConfigured() {
                return configured;
            }

            public void send(MailMessage m) {}
        };
    }

    private static MailConfig config(String selfTo) {
        return new MailConfig("smtp.example.com", 587, "user", "pass", "saiku@example.com", true, false, selfTo);
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

    @Test
    public void health_reportsSelfConfigured_andEchoesRecipient_toAnAdmin() {
        // saiku#2550e: the operator's selfTo address is an admin-only field.
        loginAdmin();
        EmailResource r = new EmailResource();
        r.setMailSender(sender(true));
        r.setMailConfig(config("ops@example.com"));
        Map<?, ?> body = (Map<?, ?>) r.health().getEntity();
        assertEquals(Boolean.TRUE, body.get("selfConfigured"));
        assertEquals("ops@example.com", body.get("to"));
    }

    /** saiku#2550e: a non-admin still learns whether mail is configured, but never the address. */
    @Test
    public void health_hidesTheRecipientFromANonAdmin() {
        login("alice", "ROLE_USER");
        EmailResource r = new EmailResource();
        r.setMailSender(sender(true));
        r.setMailConfig(config("ops@example.com"));
        Map<?, ?> body = (Map<?, ?>) r.health().getEntity();
        assertEquals(Boolean.TRUE, body.get("selfConfigured"));
        assertNull(body.get("to"));
    }

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String principal, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "x", Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private static void loginAdmin() {
        login("admin", "ROLE_ADMIN");
    }

    @Test
    public void health_reportsSelfNotConfigured_andNullRecipient_whenSelfToBlank() {
        EmailResource r = new EmailResource();
        r.setMailSender(sender(true));
        r.setMailConfig(config(null));
        Map<?, ?> body = (Map<?, ?>) r.health().getEntity();
        assertEquals(Boolean.FALSE, body.get("selfConfigured"));
        assertNull(body.get("to"));
        // mailConfig never set at all → also self-not-configured, null recipient.
        EmailResource r2 = new EmailResource();
        r2.setMailSender(sender(true));
        Map<?, ?> body2 = (Map<?, ?>) r2.health().getEntity();
        assertEquals(Boolean.FALSE, body2.get("selfConfigured"));
        assertNull(body2.get("to"));
    }
}
