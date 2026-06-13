package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.repository.AclEntry;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.service.SessionService;

/**
 * Resource-level coverage of {@link EmbedTokenResource} — mint/list/revoke
 * for opaque tokens, grant/list/revoke for public-ACL. Uses thin handcrafted
 * stubs in lieu of a mock library (matches the saiku-web convention; saiku-web
 * has no Mockito).
 */
public class EmbedTokenResourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbedTokenStore tokenStore;
    private EmbedPublicRegistry publicRegistry;
    private StubSessionService session;
    private StubDatasourceService ds;
    private StubUserService users;
    private EmbedTokenResource resource;

    @Before
    public void setUp() throws Exception {
        tokenStore = new EmbedTokenStore(tmp.newFolder("tokens").getAbsolutePath());
        publicRegistry = new EmbedPublicRegistry(tmp.newFolder("public").getAbsolutePath());
        session = new StubSessionService();
        ds = new StubDatasourceService();
        users = new StubUserService();
        resource = new EmbedTokenResource();
        resource.setTokenStore(tokenStore);
        resource.setPublicRegistry(publicRegistry);
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setUserService(users);
    }

    /* ---------------------------- mint ---------------------------- */

    @Test
    public void mint_returns_token_when_caller_can_grant() {
        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        ds.allow("/homes/admin/sales.saiku");

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/homes/admin/sales.saiku";
        req.ttlHours = 24;
        req.label = "Q4 sales";

        Response r = resource.mint(req);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("OK", body.get("status"));
        assertNotNull(body.get("token"));
        assertEquals("query", body.get("resourceKind"));
        assertEquals("/homes/admin/sales.saiku", body.get("resourcePath"));
        // Token landed in the store with the owner snapshot pinned.
        EmbedToken stored = tokenStore.load((String) body.get("token"));
        assertNotNull(stored);
        assertEquals("admin", stored.createdBy);
        assertEquals(List.of("ROLE_ADMIN"), stored.ownerRolesSnapshot);
    }

    @Test
    public void mint_rejects_caller_without_grant() {
        session.username = "bob";
        session.roles = List.of("ROLE_USER");
        // ds.allow not called → getResourceACL returns null → 403.

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/homes/admin/private.saiku";

        Response r = resource.mint(req);
        assertEquals(403, r.getStatus());
        assertEquals(0, tokenStore.listAll().size());
    }

    @Test
    public void mint_validates_kind() {
        session.username = "admin";

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "schema"; // not a valid kind
        req.resourcePath = "/x.saiku";

        Response r = resource.mint(req);
        assertEquals(400, r.getStatus());
    }

    @Test
    public void mint_validates_kind_path_suffix_pairing() {
        // query resourcePath MUST end .saiku — defence-in-depth before the
        // path ever lands in the token store.
        session.username = "admin";
        ds.allow("/homes/admin/wrong.saikudash");

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/homes/admin/wrong.saikudash";

        Response r = resource.mint(req);
        assertEquals(400, r.getStatus());
    }

    @Test
    public void mint_caps_ttl() {
        session.username = "admin";
        ds.allow("/q.saiku");

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        req.ttlHours = 9999; // exceeds 30-day cap

        Response r = resource.mint(req);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        EmbedToken stored = tokenStore.load((String) body.get("token"));
        long ttl = stored.expiresAt - stored.createdAt;
        // 30 days = 720h = 2_592_000_000 ms — anything beyond means the cap
        // didn't apply.
        assertTrue("ttl must be capped at 30 days", ttl <= 720L * 3600_000L);
    }

    /* --------------------------- revoke --------------------------- */

    @Test
    public void revoke_by_creator_succeeds() {
        session.username = "admin";
        ds.allow("/q.saiku");

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        Response r = resource.revokeToken(token);
        assertEquals(200, r.getStatus());
        assertTrue(tokenStore.load(token).revoked);
    }

    @Test
    public void revoke_by_admin_role_succeeds() {
        // Bob mints a token, admin role can revoke even though admin didn't mint.
        session.username = "bob";
        ds.allow("/q.saiku");
        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        users.adminRoles = List.of("ROLE_ADMIN");

        Response r = resource.revokeToken(token);
        assertEquals(200, r.getStatus());
    }

    @Test
    public void revoke_by_unrelated_user_is_forbidden() {
        // Admin mints; an unrelated user with no grant tries to revoke → 403.
        session.username = "admin";
        ds.allow("/q.saiku");
        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        Response mint = resource.mint(req);
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) mint.getEntity()).get("token");

        session.username = "bob";
        session.roles = List.of("ROLE_USER");

        Response r = resource.revokeToken(token);
        assertEquals(403, r.getStatus());
        assertFalse(tokenStore.load(token).revoked);
    }

    @Test
    public void revoke_unknown_id_is_404() {
        session.username = "admin";
        Response r = resource.revokeToken("definitelynotastoredtoken00");
        assertEquals(404, r.getStatus());
    }

    /* --------------------------- list ----------------------------- */

    @Test
    public void list_scopes_to_caller_by_default() {
        // allow() captures the CURRENT username — set session before
        // granting so each user gets the right paths.
        session.username = "admin";
        ds.allow("/a.saiku");
        ds.allow("/b.saiku");
        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/a.saiku";
        resource.mint(req);
        req.resourcePath = "/b.saiku";
        resource.mint(req);

        session.username = "bob";
        ds.allow("/c.saiku");
        req.resourcePath = "/c.saiku";
        resource.mint(req);

        session.username = "admin";
        Response r = resource.listTokens(false);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getEntity();
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(m -> "admin".equals(m.get("createdBy"))));
    }

    @Test
    public void list_all_for_admin_returns_every_token() {
        users.adminRoles = List.of("ROLE_ADMIN");

        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        ds.allow("/a.saiku");
        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/a.saiku";
        resource.mint(req);

        session.username = "bob";
        session.roles = List.of("ROLE_USER");
        ds.allow("/b.saiku");
        req.resourcePath = "/b.saiku";
        resource.mint(req);

        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        Response r = resource.listTokens(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getEntity();
        assertEquals(2, list.size());
    }

    /* --------------------------- public --------------------------- */

    @Test
    public void grant_public_requires_grant() {
        session.username = "bob";
        // ds.allow not called → 403

        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/exec.saikudash";

        Response r = resource.grantPublic(req);
        assertEquals(403, r.getStatus());
        assertFalse(publicRegistry.isPublic("dashboard", "/exec.saikudash"));
    }

    @Test
    public void grant_public_with_grant_persists_grant() {
        session.username = "admin";
        session.roles = List.of("ROLE_ADMIN");
        ds.allow("/exec.saikudash");

        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/exec.saikudash";
        req.label = "public exec";

        Response r = resource.grantPublic(req);
        assertEquals(200, r.getStatus());
        assertTrue(publicRegistry.isPublic("dashboard", "/exec.saikudash"));
        assertEquals("admin", publicRegistry.lookup("dashboard", "/exec.saikudash").grantedBy);
        // Owner snapshot pinned — public reads will render under this scope.
        assertEquals(List.of("ROLE_ADMIN"), publicRegistry.lookup("dashboard", "/exec.saikudash").ownerRolesSnapshot);
    }

    @Test
    public void revoke_public_by_creator_succeeds() {
        session.username = "admin";
        ds.allow("/exec.saikudash");
        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/exec.saikudash";
        resource.grantPublic(req);

        Response r = resource.revokePublic("dashboard", "/exec.saikudash");
        assertEquals(200, r.getStatus());
        assertFalse(publicRegistry.isPublic("dashboard", "/exec.saikudash"));
    }

    @Test
    public void revoke_public_unknown_is_404() {
        session.username = "admin";
        Response r = resource.revokePublic("dashboard", "/never-granted.saikudash");
        assertEquals(404, r.getStatus());
    }

    /* ------------------- saiku-cloud#940 PII gate ------------------- */

    @Test
    public void mint_without_inspector_defaults_redactionPolicy_to_tenant_default() {
        // No piiInspector wired (the existing happy-path tests already
        // exercise this — proving the new field defaults correctly).
        session.username = "admin";
        ds.allow("/q.saiku");
        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        Response r = resource.mint(req);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("TENANT_DEFAULT", body.get("redactionPolicy"));
        // Stored value matches the response.
        EmbedToken stored = tokenStore.load((String) body.get("token"));
        assertEquals(EmbedToken.RedactionPolicy.TENANT_DEFAULT, stored.redactionPolicy);
    }

    @Test
    public void mint_with_pii_resource_elevates_redactionPolicy_to_force_on() {
        // Inspector says PII present → mint succeeds with FORCE_ON.
        session.username = "admin";
        ds.allow("/sensitive.saiku");
        StubInspector inspector = new StubInspector();
        inspector.piiPaths.add("/sensitive.saiku");
        resource.setPiiInspector(inspector);

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/sensitive.saiku";
        Response r = resource.mint(req);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("FORCE_ON", body.get("redactionPolicy"));
        EmbedToken stored = tokenStore.load((String) body.get("token"));
        assertEquals(EmbedToken.RedactionPolicy.FORCE_ON, stored.redactionPolicy);
    }

    @Test
    public void mint_with_non_pii_resource_keeps_tenant_default() {
        // Inspector says clean → mint stays at TENANT_DEFAULT.
        session.username = "admin";
        ds.allow("/clean.saiku");
        resource.setPiiInspector(new StubInspector()); // no PII paths

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/clean.saiku";
        Response r = resource.mint(req);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("TENANT_DEFAULT", body.get("redactionPolicy"));
    }

    @Test
    public void grant_public_with_pii_resource_is_refused_400() {
        // The load-bearing test for this whole PR — public grants on PII
        // resources must NEVER succeed.
        session.username = "admin";
        ds.allow("/sensitive.saikudash");
        StubInspector inspector = new StubInspector();
        inspector.piiPaths.add("/sensitive.saikudash");
        resource.setPiiInspector(inspector);

        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/sensitive.saikudash";
        Response r = resource.grantPublic(req);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
        assertEquals("resourcePath", body.get("field"));
        assertTrue(((String) body.get("error")).contains("PII"));
        assertFalse(
                "public registry must remain untouched", publicRegistry.isPublic("dashboard", "/sensitive.saikudash"));
    }

    @Test
    public void grant_public_with_non_pii_resource_still_succeeds() {
        // Sanity: the gate only fires for PII. A clean resource passes.
        session.username = "admin";
        ds.allow("/clean.saikudash");
        resource.setPiiInspector(new StubInspector()); // no PII paths

        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/clean.saikudash";
        Response r = resource.grantPublic(req);
        assertEquals(200, r.getStatus());
        assertTrue(publicRegistry.isPublic("dashboard", "/clean.saikudash"));
    }

    @Test
    public void grant_public_pii_gate_fires_AFTER_grant_check() {
        // Order matters: someone without GRANT on the resource should get
        // 403 even if the resource is PII — the inspector might leak
        // structural facts about the resource via its log message if it
        // runs first. Verify the existing FORBIDDEN path still wins.
        session.username = "bob"; // no GRANT
        StubInspector inspector = new StubInspector();
        inspector.piiPaths.add("/admin-only.saikudash");
        resource.setPiiInspector(inspector);

        EmbedTokenResource.GrantRequest req = new EmbedTokenResource.GrantRequest();
        req.resourceKind = "dashboard";
        req.resourcePath = "/admin-only.saikudash";
        Response r = resource.grantPublic(req);
        assertEquals(403, r.getStatus());
        // Inspector was never asked — it's wired but the 403 short-circuits.
        assertEquals(0, inspector.calls);
    }

    @Test
    public void list_tokens_surfaces_redactionPolicy_for_admin_audit() {
        // Operator running `gh ... /tokens` needs to see WHICH tokens are
        // elevated to FORCE_ON so an audit can spot a misconfigured
        // tenant. The response payload must carry the field.
        session.username = "admin";
        ds.allow("/q.saiku");
        StubInspector inspector = new StubInspector();
        inspector.piiPaths.add("/q.saiku");
        resource.setPiiInspector(inspector);

        EmbedTokenResource.MintRequest req = new EmbedTokenResource.MintRequest();
        req.resourceKind = "query";
        req.resourcePath = "/q.saiku";
        resource.mint(req);

        Response r = resource.listTokens(false);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getEntity();
        assertEquals(1, list.size());
        assertEquals("FORCE_ON", list.get(0).get("redactionPolicy"));
    }

    /* --------------------------- stubs ---------------------------- */

    /** Minimal SessionService stub. {@code username} + {@code roles} are
     *  the only things the resource reads from the session map. */
    private static class StubSessionService extends SessionService {
        String username;
        List<String> roles = List.of();

        @Override
        public Map<String, Object> getAllSessionObjects() {
            Map<String, Object> m = new HashMap<>();
            m.put("username", username);
            m.put("roles", roles);
            return m;
        }
    }

    /** DatasourceService stub. {@code allow(path)} grants whoever the
     *  current {@code session.username} is at the moment of the call;
     *  the resource's grant check is then user-specific, mirroring the
     *  real ACL semantics ("this user can grant THIS path"). */
    private class StubDatasourceService extends DatasourceService {
        private final java.util.Set<String> allowedPaths = new java.util.HashSet<>();
        private final java.util.Map<String, java.util.Set<String>> userToPaths = new java.util.HashMap<>();

        void allow(String path) {
            allowedPaths.add(path);
            userToPaths
                    .computeIfAbsent(session.username, k -> new java.util.HashSet<>())
                    .add(path);
        }

        @Override
        public AclEntry getResourceACL(String file, String username, List<String> roles) {
            java.util.Set<String> paths = userToPaths.get(username);
            return paths != null && paths.contains(file) ? new AclEntry() : null;
        }
    }

    private static class StubUserService extends UserService {
        List<String> adminRoles = List.of();

        @Override
        public List<String> getAdminRoles() {
            return adminRoles;
        }
    }

    /** Stub inspector for the saiku-cloud#940 gate tests. Paths added to
     *  {@code piiPaths} produce a {@code referencesPii=true} result; every
     *  other path returns false. {@code calls} counts invocations so a
     *  test can assert "the gate did NOT fire because a prior check
     *  short-circuited" (e.g. the FORBIDDEN-vs-PII ordering test). */
    private static class StubInspector extends org.saiku.web.embed.EmbedPiiInspector {
        final java.util.Set<String> piiPaths = new java.util.HashSet<>();
        int calls = 0;

        StubInspector() {
            super(null, null);
        }

        @Override
        public Result inspect(String kind, String path, String owner, List<String> roles) {
            calls++;
            boolean pii = piiPaths.contains(path);
            return new Result(pii, pii ? List.of("test-cube") : List.of(), false);
        }
    }
}
