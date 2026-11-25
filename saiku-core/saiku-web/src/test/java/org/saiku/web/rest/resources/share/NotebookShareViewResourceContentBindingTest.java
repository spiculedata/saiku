/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import static org.junit.Assert.assertEquals;

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
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ContentBinding;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * The saiku#2536 scenario for shared NOTEBOOKS: a notebook cell's raw MDX runs
 * under the minter's roles, so content a collaborator (WRITE-holder) edited
 * after the link was minted — including a rewritten MDX cell — must be
 * refused, never executed.
 */
public class NotebookShareViewResourceContentBindingTest {

    private static final String PATH = "/homes/alice/nb.saikunb";
    private static final String ORIGINAL =
            "{\"cells\":[{\"id\":\"c1\",\"type\":\"mdx\",\"mdx\":\"SELECT FROM [Sales]\",\"cube\":{\"connection\":\"c\",\"catalog\":\"ct\",\"schema\":\"s\",\"cubeName\":\"Sales\"}}]}";
    private static final String TAMPERED =
            "{\"cells\":[{\"id\":\"c1\",\"type\":\"mdx\",\"mdx\":\"DRILLTHROUGH SELECT FROM [Private]\",\"cube\":{\"connection\":\"c\",\"catalog\":\"ct\",\"schema\":\"s\",\"cubeName\":\"Sales\"}}]}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareTokenStore store;
    private StubSessionService session;
    private StubDatasourceService ds;
    private NotebookShareViewResource resource;

    @Before
    public void setUp() throws Exception {
        store = new ShareTokenStore(tmp.newFolder("share").getAbsolutePath());
        session = new StubSessionService();
        ds = new StubDatasourceService();
        resource = new NotebookShareViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setShareTokenStore(store);
        // query2Resource / olapDiscoverService stay unset: the refusal paths
        // must fire before any execution surface is reached.
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void pinGuest(ShareToken t) {
        ShareGuestDetails details = new ShareGuestDetails(t.token, PATH, "alice", List.of("ROLE_USER"));
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "share-guest", details, List.of(new SimpleGrantedAuthority("ROLE_SHARE_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    public void tampered_notebook_is_refused_with_409() {
        ShareToken t =
                store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(ORIGINAL));
        pinGuest(t);
        ds.fileContent = TAMPERED; // the collaborator rewrote the MDX cell

        Response r = resource.cellQuery("c1");

        assertEquals(409, r.getStatus());
        assertEquals(ContentBinding.CONTENT_CHANGED, ((Map<?, ?>) r.getEntity()).get("status"));
    }

    @Test
    public void tampered_notebook_layout_read_is_refused_too() {
        ShareToken t =
                store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(ORIGINAL));
        pinGuest(t);
        ds.fileContent = TAMPERED;

        Response r = resource.notebook();

        assertEquals(409, r.getStatus());
    }

    @Test
    public void poisoned_record_is_refused_even_when_bytes_match_again() {
        ShareToken t =
                store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(ORIGINAL));
        pinGuest(t);
        store.onContentSaved(PATH, ContentBinding.sha256Hex(TAMPERED), "bob");
        ds.fileContent = ORIGINAL;

        Response r = resource.notebook();

        assertEquals(409, r.getStatus());
    }

    @Test
    public void minter_edited_content_still_serves() {
        ShareToken t =
                store.create(PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, ContentBinding.sha256Hex(ORIGINAL));
        pinGuest(t);
        ds.fileContent = ORIGINAL; // the parse must succeed for the serve path
        // The save hook observed the minter's own write (re-arm).
        store.onContentSaved(PATH, ContentBinding.sha256Hex(ORIGINAL), "alice");

        Response r = resource.notebook();

        assertEquals(200, r.getStatus());
    }

    /* --------------------------- stubs ---------------------------- */

    private static class StubSessionService extends SessionService {
        @Override
        public <T> T runAs(String username, List<String> roles, Supplier<T> action) {
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
}
