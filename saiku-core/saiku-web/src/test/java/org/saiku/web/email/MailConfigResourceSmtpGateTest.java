/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.user.UserService;

/**
 * saiku#1918 (17b, CWE-918) — the SMTP SSRF gate on the admin mail wizard.
 *
 * <p>The wizard used to CRLF-strip the host and persist whatever it was given; {@code /test} then
 * dialled it. Because SMTP carries no URL there is no "must be https" rule to lean on, and the
 * response distinguishes connection-refused from timeout from a protocol banner \u2014 enough to map
 * which internal host:port pairs are listening, from a tenant-admin's browser. The webhooks have had
 * that gate since saiku#1098; the mail wizard did not.
 *
 * <p>{@code SmtpHostValidatorTest} pins the rules. This pins the wiring: the gate runs on save, it
 * runs again immediately before the test-send dial, and \u2014 the part that matters most \u2014 the
 * refusal message never says WHICH check failed.
 */
public class MailConfigResourceSmtpGateTest {

    private Path home;
    private String savedHome;
    private MailConfigStore store;
    private MailConfigResource resource;

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailgate-it-");
        savedHome = System.getProperty("saiku.home");
        System.setProperty("saiku.home", home.toString());
        store = new MailConfigStore(home);
        resource = resource(k -> null);
    }

    @After
    public void tearDown() throws Exception {
        if (savedHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", savedHome);
        }
    }

    private MailConfigResource resource(Function<String, String> env) {
        MailConfigResolver resolver = new MailConfigResolver(env, k -> null, store);
        MailConfigResource r = new MailConfigResource();
        r.setUserService(new UserService() {
            @Override
            public boolean isAdmin() {
                return true;
            }
        });
        r.setMailConfigStore(store);
        r.setMailConfigResolver(resolver);
        return r;
    }

    private static MailConfigRequest req(String host, int port) {
        MailConfigRequest b = new MailConfigRequest();
        b.setHost(host);
        b.setPort(port);
        b.setUsername("mailer@example.com");
        b.setPassword("pw");
        b.setFrom("reports@example.com");
        b.setStartTls(true);
        b.setSelfTo("admin@example.com");
        return b;
    }

    @Test
    public void loopbackHostIsRefusedOnSave() {
        // The most direct probe: 127.0.0.1 is a literal, so this needs no DNS and is exactly the
        // shape a scanner tries first.
        Response resp = resource.save(req("127.0.0.1", 587));
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void aNonSmtpPortIsRefusedOnSave() {
        // Same host, legal name shape, illegal port: the port allowlist is the other half of the
        // gate, because a probe wants an odd port and mail never uses one.
        Response resp = resource.save(req("smtp.example.com", 5432));
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void aHostWithASmuggledPortIsRefusedOnSave() {
        // host:port smuggled into the host field, to slip past a validator that only checks the
        // separate port field.
        Response resp = resource.save(req("smtp.example.com:6379", 587));
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void nothingIsPersistedWhenTheHostIsRefused() {
        // The gate has to run BEFORE the store write. Refusing after persisting would leave a
        // rejected host in the encrypted config, ready for the next test-send to dial.
        resource.save(req("127.0.0.1", 587));
        assertTrue(
                "a refused host must not be written to the store",
                store.toMailConfig().isEmpty());
    }

    @Test
    public void theRefusalDoesNotSayWhichCheckFailed() {
        // "resolves to a non-routable address" vs "port not allowed" is a free oracle: it tells a
        // scanner whether the name resolved and where it pointed. One uniform message; the detail
        // goes to the server log, where the operator configuring mail can read it.
        Response resp = resource.save(req("127.0.0.1", 587));
        String body = String.valueOf(resp.getEntity());
        assertTrue("expected a single uniform message, got: " + body, body.contains("not an allowed mail relay"));
        assertTrue("must not disclose the range rule", !body.contains("127.0.0.1"));
        assertTrue("must not disclose the address", !body.toLowerCase().contains("routable"));
        assertTrue("must not disclose the port rule", !body.contains("587"));
    }

    @Test
    public void aBlankHostStillClearsTheSettingRatherThanBeingRejected() {
        // Turning SMTP OFF must keep working. A blank host is not a failed validation, it is the
        // absence of a value \u2014 refusing it would leave an operator unable to disable mail.
        Response resp = resource.save(req(null, 587));
        assertEquals(200, resp.getStatus());
        assertTrue(store.toMailConfig().isEmpty());
    }

    @Test
    public void anAllowlistedHostIsAccepted() {
        // The on-prem escape hatch: naming the relay waives the range check so an RFC-1918 mail
        // server is still configurable. Without it this change would break deployments, not harden
        // them.
        System.setProperty("saiku.mail.smtp.allowedHosts", "mail.corp");
        try {
            Response resp = resource.save(req("mail.corp", 25));
            assertEquals(200, resp.getStatus());
            assertEquals("mail.corp", store.readView().orElseThrow().host());
        } finally {
            System.clearProperty("saiku.mail.smtp.allowedHosts");
        }
    }
}
