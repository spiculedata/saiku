package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.RecipientTrustView;
import org.saiku.service.user.UserService;

/**
 * saiku#1811 PR1 — admin recipient-allowlist endpoint. Drives {@link RecipientTrustResource} against
 * a stubbed {@link UserService} and a temp-home store. Locks: admin-only (non-admin -> 403), the
 * response is a {@link RecipientTrustView}, and the store's normalisation/validation flows through.
 *
 * <p>This endpoint is DORMANT (it never sends mail); the tests only assert allowlist CRUD + the gate.
 */
public class RecipientTrustResourceTest {

    private Path home;
    private String savedHome;
    private StubUserService users;
    private RecipientTrustStore store;

    @Before
    public void setUp() throws Exception {
        home = Files.createTempDirectory("saiku-mailrcpt-it-");
        savedHome = System.getProperty("saiku.home");
        System.setProperty("saiku.home", home.toString());
        users = new StubUserService();
        store = new RecipientTrustStore(home);
    }

    @After
    public void tearDown() {
        if (savedHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", savedHome);
        }
    }

    private RecipientTrustResource resource() {
        RecipientTrustResource r = new RecipientTrustResource();
        r.setUserService(users);
        r.setRecipientTrustStore(store);
        return r;
    }

    private static RecipientTrustRequest req(List<String> addresses, List<String> domains) {
        RecipientTrustRequest b = new RecipientTrustRequest();
        b.setAddresses(addresses);
        b.setDomains(domains);
        return b;
    }

    @Test
    public void save_asAdmin_persists_andReturnsView() {
        Response resp = resource().save(req(List.of("alice@example.com"), List.of("acme.com")));
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getEntity() instanceof RecipientTrustView);
        RecipientTrustView view = (RecipientTrustView) resp.getEntity();
        assertEquals(List.of("alice@example.com"), view.addresses());
        assertEquals(List.of("acme.com"), view.domains());
        assertEquals(2, view.count());
        // Persisted: a fresh store reads it back.
        assertTrue(new RecipientTrustStore(home).isAllowed("x@acme.com"));
    }

    @Test
    public void get_asAdmin_returnsView() {
        resource().save(req(List.of("alice@example.com"), List.of("acme.com")));
        Response resp = resource().get();
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getEntity() instanceof RecipientTrustView);
        RecipientTrustView view = (RecipientTrustView) resp.getEntity();
        assertEquals(2, view.count());
    }

    @Test
    public void nonAdmin_save_isForbidden_403() {
        users.admin = false;
        Response resp = resource().save(req(List.of("alice@example.com"), List.of()));
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
    public void save_nullBody_isBadRequest_400() {
        assertEquals(400, resource().save(null).getStatus());
    }

    @Test
    public void save_dropsInvalidAndLookalikeIsNeverAllowed() {
        Response resp = resource()
                .save(req(List.of("good@example.com", "not-an-email", "bad\r\ninj@example.com"), List.of("acme.com")));
        assertEquals(200, resp.getStatus());
        RecipientTrustView view = (RecipientTrustView) resp.getEntity();
        assertEquals(List.of("good@example.com"), view.addresses());
        // The load-bearing bypass-closure holds end-to-end through the endpoint.
        assertTrue(store.isAllowed("x@acme.com"));
        assertFalse(store.isAllowed("x@evil-acme.com"));
    }

    @Test
    public void save_emptyLists_clearsAllowlist_allowsNobody() {
        resource().save(req(List.of("alice@example.com"), List.of("acme.com")));
        Response resp = resource().save(req(List.of(), List.of()));
        assertEquals(200, resp.getStatus());
        assertEquals(0, ((RecipientTrustView) resp.getEntity()).count());
        assertFalse(store.isAllowed("alice@example.com"));
        assertFalse(store.isAllowed("x@acme.com"));
    }

    /* ------------------------------ stubs ------------------------------ */

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
