/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ContentBinding;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * The saiku#2536 scenario, reproduced at the resource level: a dashboard share
 * link runs its tile queries under the MINTER's roles, so content a
 * collaborator (WRITE-holder) edited after the link was minted must be
 * refused — never executed under the minter's identity — while the minter's
 * own edits keep the link working.
 *
 * <p>Hand-rolled stubs in lieu of a mock library (saiku-web has no Mockito),
 * mirroring {@code ShareTokenResourceTest}.
 */
public class ShareViewResourceContentBindingTest {

    private static final String PATH = "/homes/alice/F/d.saikudash";
    private static final String ORIGINAL =
            "{\"id\":\"d\",\"name\":\"d\",\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/p.saiku\"}}]}}";
    private static final String TAMPERED =
            "{\"id\":\"d\",\"name\":\"d\",\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/PRIVATE.saiku\"}}]}}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareTokenStore store;
    private StubSessionService session;
    private StubDatasourceService ds;
    private StubAiQueryResource ai;
    private ShareViewResource resource;

    @Before
    public void setUp() throws Exception {
        store = new ShareTokenStore(tmp.newFolder("share").getAbsolutePath());
        session = new StubSessionService();
        ds = new StubDatasourceService();
        ai = new StubAiQueryResource();
        resource = new ShareViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setAiQueryResource(ai);
        resource.setShareTokenStore(store);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ShareToken mint(String content) {
        return store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(content));
    }

    private void pinGuest(ShareToken t) {
        ShareGuestDetails details = new ShareGuestDetails(t.token, PATH, "alice", List.of("ROLE_USER"));
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "share-guest", details, List.of(new SimpleGrantedAuthority("ROLE_SHARE_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** The exact #2536 attack: the collaborator rewrote the tile after the link
     *  was minted — the guest read must be refused, never executed. */
    @Test
    public void tampered_content_is_refused_with_409() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        ds.fileContent = TAMPERED; // bob's edit landed in the repository

        Response r = resource.tileQuery("t1");

        assertEquals(409, r.getStatus());
        assertEquals(ContentBinding.CONTENT_CHANGED, ((Map<?, ?>) r.getEntity()).get("status"));
        assertNotNull(r.getHeaderString("X-Content-Type-Options"));
        // The query was never executed — no saved request reached the AI resource.
        assertEquals(null, ai.lastSavedRequest);
    }

    @Test
    public void tampered_dashboard_layout_read_is_refused_too() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        ds.fileContent = TAMPERED;

        Response r = resource.dashboard();

        assertEquals(409, r.getStatus());
    }

    /** The minter's own edit re-arms the binding — the link keeps working. */
    @Test
    public void minter_edited_content_still_serves() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        ds.fileContent = EDITED_BY_MINTER;
        // The save hook observed the minter's own write (re-arm).
        store.onContentSaved(PATH, ContentBinding.sha256Hex(EDITED_BY_MINTER), "alice");

        Response r = resource.tileQuery("t1");

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastSavedRequest);
        assertEquals("/homes/alice/p2.saiku", ai.lastSavedRequest.getPath());
        assertEquals("alice", session.lastRunAsUser);
    }

    private static final String EDITED_BY_MINTER =
            "{\"id\":\"d\",\"name\":\"d\",\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/p2.saiku\"}}]}}";

    /** Content written by a non-minter poisons the record: even restored bytes
     *  are refused until the link is re-minted. */
    @Test
    public void poisoned_record_is_refused_even_when_bytes_match_again() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        store.onContentSaved(PATH, ContentBinding.sha256Hex(TAMPERED), "bob");
        ds.fileContent = ORIGINAL; // bob restored the exact original bytes

        Response r = resource.tileQuery("t1");

        assertEquals(409, r.getStatus());
    }

    /** Unchanged content serves exactly as before — existing links unaffected. */
    @Test
    public void unchanged_content_still_serves() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        ds.fileContent = ORIGINAL;

        Response r = resource.tileQuery("t1");

        assertEquals(200, r.getStatus());
        assertEquals("/homes/alice/p.saiku", ai.lastSavedRequest.getPath());
        assertHardened(r);
    }

    /** A token minted before this binding existed binds to the content its
     *  first post-upgrade read sees, then serves. */
    @Test
    public void legacy_unbound_token_binds_on_first_use() {
        ShareToken t = store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, null);
        pinGuest(t);
        ds.fileContent = ORIGINAL;

        Response r = resource.tileQuery("t1");

        assertEquals(200, r.getStatus());
        assertEquals(ContentBinding.sha256Hex(ORIGINAL), store.load(t.token).contentSha256);
    }

    /** A poisoned record refuses even before the content is looked at. */
    @Test
    public void dashboard_read_of_poisoned_record_is_refused() {
        ShareToken t = mint(ORIGINAL);
        pinGuest(t);
        store.onContentSaved(PATH, ContentBinding.sha256Hex(TAMPERED), "bob");
        ds.fileContent = ORIGINAL;

        Response r = resource.dashboard();

        assertEquals(409, r.getStatus());
    }

    private static void assertHardened(Response r) {
        assertTrue(r.getHeaders().keySet().contains("X-Content-Type-Options"));
        assertTrue(r.getHeaders().keySet().contains("Cache-Control"));
    }

    /* --------------------------- stubs ---------------------------- */

    private static class StubSessionService extends SessionService {
        String lastRunAsUser;
        List<String> lastRunAsRoles;

        @Override
        public <T> T runAs(String username, List<String> roles, Supplier<T> action) {
            lastRunAsUser = username;
            lastRunAsRoles = roles;
            return action.get();
        }
    }

    private static class StubDatasourceService extends DatasourceService {
        String fileContent = ORIGINAL;

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return fileContent;
        }
    }

    private static class StubAiQueryResource extends AiQueryResource {
        org.saiku.service.olap.ai.AiSavedQueryRequest lastSavedRequest;

        @Override
        public Response executeSaved(org.saiku.service.olap.ai.AiSavedQueryRequest body, String format) {
            lastSavedRequest = body;
            return Response.ok(Map.of("status", "OK"))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .build();
        }
    }
}
