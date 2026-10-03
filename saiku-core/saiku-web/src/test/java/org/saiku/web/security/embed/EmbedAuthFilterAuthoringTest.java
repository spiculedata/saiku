/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedToken;
import org.saiku.web.embed.EmbedTokenStore;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1435 — Creator Mode at the auth boundary. Two properties matter and both
 * are asserted here:
 *
 * <ol>
 *   <li>An authoring identity is issued <b>only</b> for a token that pins a cube
 *       AND a tenant, and it carries the second {@code ROLE_EMBED_AUTHOR}
 *       authority the Spring rule on {@code /embed/authoring/**} demands.</li>
 *   <li>Every other token — a query, dashboard, app or ai one, a JWT with no
 *       tenant claim, a JWT pinned to a different cube or tenant, a forged or
 *       malformed one, an anonymous public grant — gets the same opaque 401, and
 *       <b>never</b> the author role.</li>
 * </ol>
 *
 * <p>The last block is a deterministic fuzz pass: a few hundred mutated JWTs,
 * each of which must be refused without the filter ever establishing an
 * identity. A single survivor here is a remote-code path.
 */
public class EmbedAuthFilterAuthoringTest {

    private static final String SECRET = "creator-mode-test-secret-at-least-32-bytes!!";
    private static final String CUBE = "foodmart/foodmart/foodmart/sales";
    private static final String PATH = "/rest/saiku/api/embed/authoring/" + CUBE;
    /** Percent-encoded traversal in the cube-ref slot. */
    private static final String TRAVERSAL_REF =
            "/rest/saiku/api/embed/authoring/foodmart/foodmart/foodmart/..%2F..%2Fsales/context";
    /** A literal traversal step in the cube-ref slot. */
    private static final String DOTDOT_REF =
            "/rest/saiku/api/embed/authoring/foodmart/foodmart/foodmart/../sales/context";

    private EmbedTokenStore tokenStore;
    private EmbedAuthFilter filter;

    @Before
    public void setUp() {
        tokenStore = new EmbedTokenStore((String) null);
        filter = new EmbedAuthFilter(tokenStore, new EmbedPublicRegistry((String) null));
        System.setProperty(EmbedAuthFilter.PROP_JWT_SECRET, SECRET);
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        System.clearProperty(EmbedAuthFilter.PROP_JWT_SECRET);
        System.clearProperty(EmbedPublicRegistry.ALLOW_PUBLIC_PROP);
    }

    /* ------------------------ the happy path ------------------------ */

    @Test
    public void authoring_jwt_yields_guest_plus_author_roles() throws Exception {
        ContextCapturingChain chain = doGet(PATH + "/context", jwt("acme", CUBE));

        assertTrue(chain.called);
        EmbedGuestDetails d = details(chain.capturedAuth);
        assertTrue(d.isAuthoring());
        assertEquals("acme", d.tenantId);
        assertEquals("/" + CUBE, d.resourcePath);
        assertTrue(roles(chain.capturedAuth).contains(EmbedAuthFilter.AUTHOR_ROLE));
        assertTrue(roles(chain.capturedAuth).contains(EmbedAuthFilter.GUEST_ROLE));
    }

    @Test
    public void authoring_jwt_may_post() throws Exception {
        ContextCapturingChain chain = doPost(PATH + "/query", jwt("acme", CUBE), "{}");
        assertTrue("a valid authoring token must be able to save", chain.called);
    }

    @Test
    public void opaque_authoring_token_yields_the_author_role() throws Exception {
        EmbedToken t = tokenStore.create(
                "authoring", "/" + CUBE, "admin", List.of("ROLE_ADMIN"), 3600_000L, "creator", null, "acme");
        ContextCapturingChain chain = doGet(PATH + "/context", t.token);

        assertTrue(chain.called);
        assertEquals("acme", details(chain.capturedAuth).tenantId);
        assertTrue(roles(chain.capturedAuth).contains(EmbedAuthFilter.AUTHOR_ROLE));
    }

    /* -------------------------- the refusals -------------------------- */

    @Test
    public void jwt_without_a_tenant_claim_is_refused() throws Exception {
        String jwt = mint("{\"sub\":\"u\",\"saiku.resourceKind\":\"authoring\","
                + "\"saiku.resourcePath\":\"/" + CUBE + "\",\"exp\":" + future() + "}");
        assertRefused(doGet(PATH + "/context", jwt));
    }

    @Test
    public void jwt_with_a_traversal_tenant_claim_is_refused() throws Exception {
        for (String tenant : new String[] {"../other", "a/b", "a\\b", "..", "", "x".repeat(80)}) {
            assertRefused(doGet(PATH + "/context", jwt(tenant, CUBE)));
        }
    }

    @Test
    public void jwt_pinned_to_another_cube_is_refused() throws Exception {
        // Tenant A's token replayed against a different cube in the same schema.
        assertRefused(doGet("/rest/saiku/api/embed/authoring/foodmart/foodmart/foodmart/other", jwt("acme", CUBE)));
    }

    @Test
    public void tenant_b_cannot_reach_tenant_as_pinned_folder() throws Exception {
        // The token pins a cube, never a folder, so tenant B's token against
        // tenant A's URL shape still resolves to B's own scope server-side.
        ContextCapturingChain chain = doGet(PATH + "/context", jwt("b", CUBE));
        assertEquals("b", details(chain.capturedAuth).tenantId);
    }

    @Test
    public void a_read_token_never_gets_the_author_role() throws Exception {
        EmbedToken t = tokenStore.create(
                "query", "/homes/admin/sales.saiku", "admin", List.of("ROLE_ADMIN"), 3600_000L, "read", null, null);
        ContextCapturingChain chain = doGet("/rest/saiku/api/embed/query/homes/admin/sales.saiku", t.token);

        assertTrue(chain.called);
        assertEquals(List.of(EmbedAuthFilter.GUEST_ROLE), roles(chain.capturedAuth).stream().sorted().toList());
        assertNull("a read token must carry no tenant", details(chain.capturedAuth).tenantId);
        assertFalse(details(chain.capturedAuth).isAuthoring());
    }

    @Test
    public void a_read_token_cannot_reach_the_authoring_prefix() throws Exception {
        EmbedToken t = tokenStore.create(
                "query", "/" + CUBE, "admin", List.of("ROLE_ADMIN"), 3600_000L, "read", null, null);
        assertRefused(doGet(PATH + "/context", t.token));
    }

    @Test
    public void an_unlisted_verb_is_refused_without_an_identity() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("TRACE", PATH + "/query");
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, jwt("acme", CUBE));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
    }

    @Test
    public void a_malformed_cube_ref_is_never_authorised() throws Exception {
        // Fewer than the 4 cube-ref segments: there is no cube to pin.
        assertNotAuthorised(
                doGet("/rest/saiku/api/embed/authoring/foodmart/foodmart/sales/context", jwt("acme", CUBE)));
        // A traversal segment smuggled into the ref. The filter declines to parse
        // a target, so the request falls through to the Spring chain — which has
        // no author rule for it and refuses. Either way: no author role.
        assertNotAuthorised(doGet(TRAVERSAL_REF, jwt("acme", CUBE)));
        // A cube ref segment that is literally a traversal step.
        assertNotAuthorised(doGet(DOTDOT_REF, jwt("acme", CUBE)));
    }

    @Test
    public void an_anonymous_public_grant_never_authors() throws Exception {
        // Even with public embeds switched on, there is no public authoring
        // path: an anonymous visitor must not acquire a write scope.
        System.setProperty(EmbedPublicRegistry.ALLOW_PUBLIC_PROP, "true");
        assertNotAuthorised(doGet(PATH + "/context", null));
    }

    @Test
    public void no_secret_means_no_authoring() throws Exception {
        System.clearProperty(EmbedAuthFilter.PROP_JWT_SECRET);
        assertRefused(doGet(PATH + "/context", jwt("acme", CUBE)));
    }

    /* ----------------------------- fuzz ----------------------------- */

    /**
     * saiku#1435 acceptance criterion: a crafted JWT with mismatched tenant /
     * cube claims must 401. Deterministic (fixed seed) so a failure is
     * reproducible, and broad enough that every claim the filter reads is
     * mutated at least once.
     */
    @Test
    public void mutated_jwts_are_all_refused() throws Exception {
        Random rnd = new Random(1435L);
        String[] tenants = {"acme", "", "..", "../acme", "a/b", "a b", "x".repeat(70), "acme ", " acme", "acme\n"};
        // All well-formed 4-segment cube refs, so a refusal is a genuine 401 from
        // the filter rather than a "declined to parse" pass-through.
        String[] cubes = {
            CUBE, "foodmart/foodmart/foodmart/other", "a/b/c/d", "x/y/z/w", "foodmart/foodmart/foodmart/sales2"
        };
        String[] kinds = {"authoring", "query", "AUTHORING", "", "authoring "};
        int admitted = 0;
        int refused = 0;

        // Control: prove the harness CAN see an admission, so "no survivor" in
        // the loop below means something.
        ContextCapturingChain control = doGet(PATH + "/context", jwt("acme", CUBE));
        assertTrue("control JWT must author", control.capturedAuth != null
                && roles(control.capturedAuth).contains(EmbedAuthFilter.AUTHOR_ROLE));
        admitted++;

        for (int i = 0; i < 300; i++) {
            String tenant = tenants[rnd.nextInt(tenants.length)];
            String cube = cubes[rnd.nextInt(cubes.length)];
            String kind = kinds[rnd.nextInt(kinds.length)];
            String payload = "{\"sub\":\"u" + i + "\",\"saiku.resourceKind\":\"" + kind + "\","
                    + "\"saiku.resourcePath\":\"/" + cube + "\","
                    + (rnd.nextBoolean() ? "\"saiku.tenantId\":\"" + tenant + "\"," : "")
                    + "\"exp\":" + (rnd.nextBoolean() ? future() : past()) + "}";
            String secret = rnd.nextInt(10) == 0 ? "a-different-secret-key-of-32-bytes!!" : SECRET;

            // Aim the request at either the token's own cube or a random one —
            // both directions of the pin must be checked.
            String urlCube = rnd.nextBoolean() ? CUBE : cube;
            String url = "/rest/saiku/api/embed/authoring/" + urlCube + "/context";
            ContextCapturingChain chain = doGet(url, mint(payload, secret));

            boolean authorised = chain.called && chain.capturedAuth != null
                    && roles(chain.capturedAuth).contains(EmbedAuthFilter.AUTHOR_ROLE);
            // The ONLY shape that may author: the claim's cube matches the cube
            // the URL targets, the kind is exactly "authoring", the tenant is a
            // usable id, and the signature is the deployment's key.
            boolean consistent =
                    "authoring".equals(kind) && urlCube.equals(cube) && "acme".equals(tenant) && SECRET.equals(secret);
            if (authorised && !consistent) {
                // A survivor here would be a remote capability.
                fail("inconsistent JWT was admitted: " + payload + " -> " + url);
            }
            if (authorised) {
                admitted++;
            } else {
                refused++;
                assertFalse("a non-admitted mutation must never reach the chain", chain.called);
                assertEquals("non-2xx must be the opaque 401", 401, chain.response.getStatus());
            }
        }
        assertTrue("the fuzz pass should mostly refuse", refused > 200);
        assertTrue("only fully-consistent shapes may author, and there are few: " + admitted, admitted <= 5);
    }

    /* --------------------------- helpers --------------------------- */

    private static void fail(String message) {
        org.junit.Assert.fail(message);
    }

    private String jwt(String tenantId, String cube) {
        return mint("{\"sub\":\"u\",\"saiku.resourceKind\":\"authoring\","
                + "\"saiku.resourcePath\":\"/" + cube + "\","
                + "\"saiku.owner\":\"admin\",\"saiku.ownerRoles\":[\"ROLE_ADMIN\"],"
                + "\"saiku.tenantId\":\"" + tenantId + "\","
                + "\"exp\":" + future() + "}");
    }

    private static long future() {
        return System.currentTimeMillis() / 1000L + 3600;
    }

    private static long past() {
        return System.currentTimeMillis() / 1000L - 3600;
    }

    private static String mint(String payloadJson) {
        return mint(payloadJson, SECRET);
    }

    private static String mint(String payloadJson, String secret) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String h = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String p = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return h + "." + p + "." + b64.encodeToString(mac.doFinal((h + "." + p).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ContextCapturingChain doGet(String url, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", url);
        if (token != null) {
            req.addHeader(EmbedAuthFilter.TOKEN_HEADER, token);
        }
        return run(req);
    }

    private ContextCapturingChain doPost(String url, String token, String body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", url);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.addHeader(EmbedAuthFilter.TOKEN_HEADER, token);
        return run(req);
    }

    private ContextCapturingChain run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ContextCapturingChain chain = new ContextCapturingChain();
        chain.response = resp;
        filter.doFilter(req, resp, chain);
        return chain;
    }

    private static void assertRefused(ContextCapturingChain chain) {
        assertFalse("no identity may be established", chain.called);
        assertEquals(401, chain.response.getStatus());
        assertTrue(chain.response.getContentAsString().contains("EMBED_INVALID"));
    }

    /** For requests the filter declines to turn into a target at all: the
     *  identity must be absent (or at least carry no author role) so the Spring
     *  chain is what refuses them. */
    private static void assertNotAuthorised(ContextCapturingChain chain) {
        if (!chain.called) {
            assertEquals(401, chain.response.getStatus());
            return;
        }
        Authentication auth = chain.capturedAuth;
        if (auth == null) {
            return;
        }
        assertFalse(
                "a pass-through request must never carry the author role",
                roles(auth).contains(EmbedAuthFilter.AUTHOR_ROLE));
    }

    private static EmbedGuestDetails details(Authentication auth) {
        assertNotNull(auth);
        Object d = auth.getDetails();
        assertTrue(d instanceof EmbedGuestDetails);
        return (EmbedGuestDetails) d;
    }

    private static List<String> roles(Authentication auth) {
        List<String> out = new ArrayList<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            out.add(a.getAuthority());
        }
        return out;
    }

    /** Captures the SecurityContext as the downstream resource would see it, and
     *  keeps the response so a refusal can be asserted on. */
    private static class ContextCapturingChain extends MockFilterChain {
        boolean called = false;
        Authentication capturedAuth;
        MockHttpServletResponse response;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
            called = true;
            capturedAuth = SecurityContextHolder.getContext().getAuthentication();
            response = (MockHttpServletResponse) resp;
        }
    }
}
