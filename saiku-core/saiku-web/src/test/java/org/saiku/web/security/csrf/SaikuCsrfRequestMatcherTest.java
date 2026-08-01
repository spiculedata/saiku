/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.csrf;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link SaikuCsrfRequestMatcher}. {@code matches() == true} means
 * "CSRF protection applies to this request"; {@code false} means "exempt".
 *
 * <p>Covers the token-only guest-read exemptions (saiku#1441 follow-up): the
 * {@code <saiku-embed>} bundle and share links POST with {@code credentials:"omit"}
 * and only their token header, so they carry no ambient cookie for a forged request
 * to ride — CSRF is inapplicable. The exemption is deliberately narrow (gated on the
 * token header AND scoped to the read prefixes) so the session-authenticated mint /
 * public / revoke surfaces keep CSRF.
 */
public class SaikuCsrfRequestMatcherTest {

    private static final String EMBED_HEADER = "X-Saiku-Embed-Token";
    private static final String SHARE_HEADER = "X-Saiku-Share-Token";

    private final SaikuCsrfRequestMatcher matcher = new SaikuCsrfRequestMatcher();

    private static MockHttpServletRequest req(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    /* --------------------------- baseline behaviour --------------------------- */

    @Test
    public void safeMethodsAreNeverChecked() {
        assertFalse(matcher.matches(req("GET", "/rest/saiku/api/repository")));
        assertFalse(matcher.matches(req("HEAD", "/rest/saiku/api/repository")));
        assertFalse(matcher.matches(req("OPTIONS", "/rest/saiku/api/repository")));
    }

    @Test
    public void statefulRestPostRequiresCsrf() {
        assertTrue(matcher.matches(req("POST", "/rest/saiku/api/repository/resource")));
    }

    @Test
    public void authorizationHeaderIsExempt() {
        MockHttpServletRequest r = req("POST", "/rest/saiku/api/repository/resource");
        r.addHeader("Authorization", "Basic YWRtaW46YWRtaW4=");
        assertFalse(matcher.matches(r));
    }

    @Test
    public void loginEndpointIsExempt() {
        assertFalse(matcher.matches(req("POST", "/rest/saiku/session")));
        assertFalse(matcher.matches(req("POST", "/rest/saiku/session/clear")));
    }

    @Test
    public void nonRestPostIsNotChecked() {
        assertFalse(matcher.matches(req("POST", "/ui/whatever")));
    }

    /* --------------------- embed guest-read exemption (NEW) -------------------- */

    @Test
    public void embedTileQueryPostWithTokenIsExempt() {
        MockHttpServletRequest r =
                req("POST", "/rest/saiku/api/embed/app/homes/home:admin/sales.saikuapp/page/p1/tile/t1/query");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertFalse("token-only embed tile POST must be CSRF-exempt", matcher.matches(r));
    }

    @Test
    public void embedDashboardTilePostWithTokenIsExempt() {
        MockHttpServletRequest r =
                req("POST", "/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash/tile/t9/query");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertFalse(matcher.matches(r));
    }

    @Test
    public void embedFilteredQueryPostWithTokenIsExempt() {
        MockHttpServletRequest r = req("POST", "/rest/saiku/api/embed/query/homes/admin/sales.saiku");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertFalse(matcher.matches(r));
    }

    @Test
    public void embedReadPostWithoutTokenStillRequiresCsrf() {
        // A logged-in user hitting the read prefix without the token header falls through
        // to the isFullyAuthenticated() branch — CSRF must still apply (no exemption).
        assertTrue(matcher.matches(
                req("POST", "/rest/saiku/api/embed/app/homes/home:admin/sales.saikuapp/page/p1/tile/t1/query")));
    }

    @Test
    public void embedMintPostWithTokenHeaderStillRequiresCsrf() {
        // The mint surface is session-authenticated. Even if a caller sets the token header,
        // /embed/tokens is NOT a read prefix, so CSRF stays enforced.
        MockHttpServletRequest r = req("POST", "/rest/saiku/api/embed/tokens");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertTrue("mint surface must keep CSRF even with a token header present", matcher.matches(r));
    }

    @Test
    public void embedPublicPostWithTokenHeaderStillRequiresCsrf() {
        MockHttpServletRequest r = req("POST", "/rest/saiku/api/embed/public");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertTrue(matcher.matches(r));
    }

    @Test
    public void embedTokenHeaderOnUnrelatedRestPathStillRequiresCsrf() {
        // Forged header on a sensitive mutation endpoint must not skip CSRF.
        MockHttpServletRequest r = req("POST", "/rest/saiku/api/repository/resource");
        r.addHeader(EMBED_HEADER, "tok-abc");
        assertTrue("token header must not exempt a non-embed path", matcher.matches(r));
    }

    @Test
    public void emptyEmbedTokenHeaderStillRequiresCsrf() {
        MockHttpServletRequest r =
                req("POST", "/rest/saiku/api/embed/app/homes/home:admin/sales.saikuapp/page/p1/tile/t1/query");
        r.addHeader(EMBED_HEADER, "");
        assertTrue(matcher.matches(r));
    }

    /* --------------------- share guest-read exemption (NEW) -------------------- */

    @Test
    public void shareViewTilePostWithTokenIsExempt() {
        MockHttpServletRequest r = req("POST", "/rest/saiku/share/view/tile/t3/query");
        r.addHeader(SHARE_HEADER, "shr-abc");
        assertFalse(matcher.matches(r));
    }

    @Test
    public void shareMintPostWithTokenHeaderStillRequiresCsrf() {
        // /share/** (not /share/view/**) is the owner-only mint/revoke surface — keep CSRF.
        MockHttpServletRequest r = req("POST", "/rest/saiku/share");
        r.addHeader(SHARE_HEADER, "shr-abc");
        assertTrue(matcher.matches(r));
    }

    @Test
    public void shareTokenHeaderOnEmbedPathDoesNotExempt() {
        // Cross-wired header/path: the share header does not exempt an embed path (and vice versa).
        MockHttpServletRequest r =
                req("POST", "/rest/saiku/api/embed/app/homes/home:admin/sales.saikuapp/page/p1/tile/t1/query");
        r.addHeader(SHARE_HEADER, "shr-abc");
        assertTrue(matcher.matches(r));
    }
}
