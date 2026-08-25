/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.mail.MailConfigView;
import org.saiku.service.user.UserService;

/**
 * saiku#943 P0-B — admin mail-config endpoint. Drives {@link MailConfigResource} against a stubbed
 * {@link UserService} and a temp-home store. Locks the SEC-load-bearing behaviour: admin-only,
 * response is a {@link MailConfigView} (never the password), env-wins (ops-managed save refused),
 * input validation.
 */
public class MailConfigResourceTest {

    private static final String SECRET = "endpoint-smtp-pw";

    private Path home;
    private String savedHome;
    private StubUserService users;
    private MailConfigStore store;

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailcfg-it-");
        savedHome = System.getProperty("saiku.home");
        System.setProperty("saiku.home", home.toString());
        users = new StubUserService();
        store = new MailConfigStore(home);
    }

    @After
    public void tearDown() throws Exception {
        if (savedHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", savedHome);
        }
    }

    /** Resource whose resolver sees the given env (for the env-wins cases); default = no env. */
    private MailConfigResource resource(Function<String, String> env) {
        MailConfigResolver resolver = new MailConfigResolver(env, k -> null, store);
        MailConfigResource r = new MailConfigResource();
        r.setUserService(users);
        r.setMailConfigStore(store);
        r.setMailConfigResolver(resolver);
        return r;
    }

    private MailConfigResource resource() {
        return resource(k -> null);
    }

    private static MailConfigRequest req(String password) {
        MailConfigRequest b = new MailConfigRequest();
        b.setHost("smtp.example.com");
        b.setPort(587);
        b.setUsername("mailer@example.com");
        b.setPassword(password);
        b.setFrom("reports@example.com");
        b.setStartTls(true);
        b.setSsl(false);
        b.setSelfTo("admin@example.com");
        return b;
    }

    @Test
    public void save_asAdmin_persists_andReturnsRedactedView() {
        Response resp = resource().save(req(SECRET));
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getEntity() instanceof MailConfigView);
        MailConfigView view = (MailConfigView) resp.getEntity();
        assertTrue(view.passwordSet());
        assertEquals("smtp.example.com", view.host());
        // The response must NEVER carry the plaintext (or ciphertext) password.
        assertFalse(view.toString().contains(SECRET));
        // And it round-trips: the sender can decrypt the persisted secret.
        assertEquals(SECRET, store.toMailConfig().orElseThrow().password());
    }

    @Test
    public void save_encryptsAtRest_plaintextNeverOnDisk() throws Exception {
        resource().save(req(SECRET));
        String onDisk = Files.readString(home.resolve("mail-config.json"));
        assertFalse("plaintext password must never hit disk", onDisk.contains(SECRET));
        assertTrue(
                "stored password must be v2: AES-GCM ciphertext",
                store.read().orElseThrow().getPassword().startsWith("v2:"));
    }

    @Test
    public void get_asAdmin_returnsView_notThePassword() {
        resource().save(req(SECRET));
        Response resp = resource().get();
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getEntity() instanceof MailConfigView);
        assertFalse(resp.getEntity().toString().contains(SECRET));
    }

    @Test
    public void nonAdmin_save_isForbidden_403() {
        users.admin = false;
        Response resp = resource().save(req(SECRET));
        assertEquals(403, resp.getStatus());
        // Nothing was written.
        assertFalse(store.exists());
    }

    @Test
    public void nonAdmin_get_isForbidden_403() {
        users.admin = false;
        assertEquals(403, resource().get().getStatus());
    }

    @Test
    public void save_whenOpsManaged_isRefused_409() {
        Function<String, String> env = envOf(Map.of(
                "SAIKU_MAIL_SMTP_HOST", "env.smtp.example",
                "SAIKU_MAIL_FROM", "env-from@example.com"));
        Response resp = resource(env).save(req(SECRET));
        assertEquals(409, resp.getStatus());
        // Env-wins: the in-app save must not shadow the ops-managed deployment.
        assertFalse(store.exists());
    }

    @Test
    public void save_invalidFromAddress_isBadRequest_400() {
        MailConfigRequest b = req(SECRET);
        b.setFrom("not-an-email");
        Response resp = resource().save(b);
        assertEquals(400, resp.getStatus());
        assertFalse(store.exists());
    }

    @Test
    public void save_nullBody_isBadRequest_400() {
        assertEquals(400, resource().save(null).getStatus());
    }

    @Test
    public void save_stripsCrlfFromHost_noHeaderInjection() {
        MailConfigRequest b = req(SECRET);
        b.setHost("smtp.example.com\r\nInjected: x");
        Response resp = resource().save(b);
        assertEquals(200, resp.getStatus());
        assertFalse(store.read().orElseThrow().getHost().contains("\r"));
        assertFalse(store.read().orElseThrow().getHost().contains("\n"));
    }

    /* ------------------------------ stubs ------------------------------ */

    private static Function<String, String> envOf(Map<String, String> m) {
        return m::get;
    }

    private static final class StubUserService extends UserService {
        boolean admin = true;

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
