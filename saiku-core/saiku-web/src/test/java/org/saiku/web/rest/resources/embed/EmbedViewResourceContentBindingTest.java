/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ContentBinding;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * The saiku#2536 scenario for EMBED tokens and public embed grants: an embed
 * read executes the pinned resource's queries under the minter's roles, so
 * content a collaborator (WRITE-holder) edited after the token was minted
 * must be refused — across the query, dashboard and app read paths — while
 * the minter's own edits keep the token working.
 */
public class EmbedViewResourceContentBindingTest {

    private static final String QUERY_PATH = "/homes/alice/q.saiku";
    private static final String DASH_PATH = "/homes/alice/d.saikudash";
    private static final String APP_PATH = "/homes/alice/a.saikuapp";
    private static final String REFERENCE_PATH = "/homes/alice/p.saiku";
    private static final String ORIGINAL_QUERY = "{\"mq\":{\"type\":\"QUERYMODEL\"}}";
    private static final String TAMPERED_QUERY = "{\"mq\":{\"type\":\"MDX\",\"mdx\":\"DRILLTHROUGH ...\"}}";
    private static final String ORIGINAL_DASH =
            "{\"id\":\"d\",\"name\":\"d\",\"layout\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/p.saiku\"}}]}}";
    private static final String ORIGINAL_APP = "{\"pages\":[{\"id\":\"pg1\",\"grid\":{\"tiles\":[]}}]}";
    private static final String APP_WITH_REFERENCE =
            "{\"pages\":[{\"id\":\"pg1\",\"grid\":{\"tiles\":[{\"id\":\"t1\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/p.saiku\"}}]}}]}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbedTokenStore tokens;
    private EmbedPublicRegistry grants;
    private StubDatasourceService ds;
    private StubSessionService session;
    private StubAiQueryResource ai;
    private EmbedViewResource resource;

    @Before
    public void setUp() throws Exception {
        tokens = new EmbedTokenStore(tmp.newFolder("embeds").getAbsolutePath());
        grants = new EmbedPublicRegistry(tmp.newFolder("public").getAbsolutePath());
        ds = new StubDatasourceService();
        session = new StubSessionService();
        ai = new StubAiQueryResource();
        resource = new EmbedViewResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setAiQueryResource(ai);
        resource.setTokenStore(tokens);
        resource.setPublicRegistry(grants);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void pinGuest(String tokenId, String kind, String path) {
        EmbedGuestDetails details = new EmbedGuestDetails(tokenId, kind, path, "alice", List.of("ROLE_USER"));
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /* ------------------------- query kind ------------------------- */

    @Test
    public void tampered_saved_query_is_refused_with_409() {
        EmbedToken t = tokens.create(
                "query",
                QUERY_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                ContentBinding.sha256Hex(ORIGINAL_QUERY));
        pinGuest(t.token, "query", QUERY_PATH);
        ds.fileContent = TAMPERED_QUERY; // the collaborator's rewrite

        Response r = resource.query("homes/alice/q.saiku", null);

        assertEquals(409, r.getStatus());
        assertEquals(ContentBinding.CONTENT_CHANGED, ((Map<?, ?>) r.getEntity()).get("status"));
        assertNull(ai.lastSavedRequest); // never executed under the minter's roles
    }

    @Test
    public void unchanged_saved_query_still_serves() {
        EmbedToken t = tokens.create(
                "query",
                QUERY_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                ContentBinding.sha256Hex(ORIGINAL_QUERY));
        pinGuest(t.token, "query", QUERY_PATH);
        ds.fileContent = ORIGINAL_QUERY;

        Response r = resource.query("homes/alice/q.saiku", null);

        assertEquals(200, r.getStatus());
        assertEquals(QUERY_PATH, ai.lastSavedRequest.getPath());
        assertEquals("alice", session.lastRunAsUser);
    }

    @Test
    public void poisoned_embed_token_refuses_restored_bytes() {
        EmbedToken t = tokens.create(
                "query",
                QUERY_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                ContentBinding.sha256Hex(ORIGINAL_QUERY));
        pinGuest(t.token, "query", QUERY_PATH);
        tokens.onContentSaved(QUERY_PATH, ContentBinding.sha256Hex(TAMPERED_QUERY), "bob");
        ds.fileContent = ORIGINAL_QUERY;

        assertEquals(409, resource.query("homes/alice/q.saiku", null).getStatus());
    }

    @Test
    public void minter_edited_saved_query_still_serves() {
        EmbedToken t = tokens.create(
                "query",
                QUERY_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                ContentBinding.sha256Hex(ORIGINAL_QUERY));
        pinGuest(t.token, "query", QUERY_PATH);
        ds.fileContent = TAMPERED_QUERY;
        tokens.onContentSaved(QUERY_PATH, ContentBinding.sha256Hex(TAMPERED_QUERY), "alice");

        assertEquals(200, resource.query("homes/alice/q.saiku", null).getStatus());
    }

    /* ----------------------- public grants ------------------------ */

    @Test
    public void tampered_public_grant_is_refused() {
        grants.grant(
                "dashboard", DASH_PATH, "alice", List.of("ROLE_USER"), null, ContentBinding.sha256Hex(ORIGINAL_DASH));
        pinGuest(null, "dashboard", DASH_PATH); // token=null → the public-grant path
        ds.fileContent = ORIGINAL_DASH.replace("/homes/alice/p.saiku", "/homes/alice/PRIVATE.saiku");

        Response r = resource.dashboard("homes/alice/d.saikudash");

        assertEquals(409, r.getStatus());
    }

    @Test
    public void collaborator_rewriting_only_dashboard_reference_is_refused_for_token() {
        ds.files.put(DASH_PATH, ORIGINAL_DASH);
        ds.files.put(REFERENCE_PATH, ORIGINAL_QUERY);
        ContentBinding.Snapshot binding = ContentBinding.snapshot(
                "dashboard", ORIGINAL_DASH, path -> ds.getFileData(path, "alice", List.of("ROLE_USER")));
        EmbedToken t = tokens.create(
                "dashboard",
                DASH_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                binding.contentSha256(),
                binding.referenceSha256());
        pinGuest(t.token, "dashboard", DASH_PATH);
        ds.files.put(REFERENCE_PATH, TAMPERED_QUERY);

        assertEquals(
                409, resource.tileQuery("homes/alice/d.saikudash", "t1", null).getStatus());
        assertNull(ai.lastSavedRequest);
    }

    @Test
    public void collaborator_rewriting_only_dashboard_reference_poisons_public_grant() {
        ds.files.put(DASH_PATH, ORIGINAL_DASH);
        ds.files.put(REFERENCE_PATH, ORIGINAL_QUERY);
        ContentBinding.Snapshot binding = ContentBinding.snapshot(
                "dashboard", ORIGINAL_DASH, path -> ds.getFileData(path, "alice", List.of("ROLE_USER")));
        grants.grant(
                "dashboard",
                DASH_PATH,
                "alice",
                List.of("ROLE_USER"),
                null,
                binding.contentSha256(),
                binding.referenceSha256());
        pinGuest(null, "dashboard", DASH_PATH);
        org.saiku.web.share.SharedContentBindingListener listener =
                new org.saiku.web.share.SharedContentBindingListener();
        listener.setEmbedPublicRegistry(grants);
        listener.onFileSaved(REFERENCE_PATH, TAMPERED_QUERY, "bob", ds);

        assertEquals(
                409, resource.tileQuery("homes/alice/d.saikudash", "t1", null).getStatus());
        assertNull(ai.lastSavedRequest);
    }

    @Test
    public void unchanged_public_grant_still_serves() {
        grants.grant(
                "dashboard", DASH_PATH, "alice", List.of("ROLE_USER"), null, ContentBinding.sha256Hex(ORIGINAL_DASH));
        pinGuest(null, "dashboard", DASH_PATH);
        ds.fileContent = ORIGINAL_DASH;

        assertEquals(200, resource.dashboard("homes/alice/d.saikudash").getStatus());
    }

    /* -------------------------- app kind -------------------------- */

    @Test
    public void tampered_app_doc_is_refused() {
        EmbedToken t = tokens.create(
                "app",
                APP_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                ContentBinding.sha256Hex(ORIGINAL_APP));
        pinGuest(t.token, "app", APP_PATH);
        ds.fileContent =
                "{\"pages\":[{\"id\":\"pg1\",\"grid\":{\"tiles\":[{\"id\":\"x\",\"query\":{\"kind\":\"reference\",\"path\":\"/homes/alice/PRIVATE.saiku\"}}]}}]}";

        Response r = resource.app("homes/alice/a.saikuapp");

        assertEquals(409, r.getStatus());
    }

    @Test
    public void collaborator_rewriting_only_app_reference_is_refused() {
        ds.files.put(APP_PATH, APP_WITH_REFERENCE);
        ds.files.put(REFERENCE_PATH, ORIGINAL_QUERY);
        ContentBinding.Snapshot binding = ContentBinding.snapshot(
                "app", APP_WITH_REFERENCE, path -> ds.getFileData(path, "alice", List.of("ROLE_USER")));
        EmbedToken t = tokens.create(
                "app",
                APP_PATH,
                "alice",
                List.of("ROLE_USER"),
                3600_000L,
                null,
                null,
                null,
                binding.contentSha256(),
                binding.referenceSha256());
        pinGuest(t.token, "app", APP_PATH);
        ds.files.put(REFERENCE_PATH, TAMPERED_QUERY);

        assertEquals(
                409,
                resource.appTileQuery("homes/alice/a.saikuapp", "pg1", "t1", null)
                        .getStatus());
        assertNull(ai.lastSavedRequest);
    }

    /* --------------------- legacy + JWT reads --------------------- */

    @Test
    public void legacy_unbound_embed_token_binds_on_first_use() {
        EmbedToken t =
                tokens.create("query", QUERY_PATH, "alice", List.of("ROLE_USER"), 3600_000L, null, null, null, null);
        pinGuest(t.token, "query", QUERY_PATH);
        ds.fileContent = ORIGINAL_QUERY;

        assertEquals(200, resource.query("homes/alice/q.saiku", null).getStatus());
        assertEquals(ContentBinding.sha256Hex(ORIGINAL_QUERY), tokens.load(t.token).contentSha256);
    }

    @Test
    public void jwt_read_has_no_record_and_proceeds() {
        // The embed-JWT path carries the raw compact JWT in the token slot —
        // no store record exists, and the path pin from the JWT target is the
        // only scope there. The binding must NOT refuse such reads.
        pinGuest("eyJhbGciOiJub25lIn0.payload.signature", "query", QUERY_PATH);
        ds.fileContent = ORIGINAL_QUERY;

        Response r = resource.query("homes/alice/q.saiku", null);

        assertEquals(200, r.getStatus());
        assertNotNull(ai.lastSavedRequest);
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
        String fileContent = "{}";
        Map<String, String> files = new HashMap<>();

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return files.getOrDefault(path, files.getOrDefault("/" + path, fileContent));
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
