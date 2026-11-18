/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Evaluates the REAL {@code <intercept-url>} rules of the main chain in
 * {@code applicationContext-saiku.xml} (first match wins, as Spring does) against guest and
 * user principals (#2343). The minting filters only confine a guest by a URI-prefix check; this
 * pins that the URL rules alone also keep a guest on its own prefix.
 */
public class SpringUrlRulesGuestExclusionTest {

    private static final File CONTEXT =
            new File("../../saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml");

    private record Rule(AntPathRequestMatcher matcher, WebExpressionAuthorizationManager manager) {}

    private static List<Rule> mainChain;
    private static List<Rule> xmlaChain;

    @BeforeClass
    public static void loadRules() throws Exception {
        assertTrue("missing " + CONTEXT.getAbsolutePath(), CONTEXT.isFile());
        NodeList https = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(CONTEXT)
                .getElementsByTagName("security:http");
        for (int i = 0; i < https.getLength(); i++) {
            Element http = (Element) https.item(i);
            List<Rule> rules = rulesOf(http);
            if (rules.isEmpty()) {
                continue;
            }
            if ("/xmla/**".equals(http.getAttribute("pattern"))) {
                xmlaChain = rules;
            } else if (http.getAttribute("pattern").isEmpty() && mainChain == null) {
                mainChain = rules;
            }
        }
        assertNotNull(mainChain);
        assertNotNull(xmlaChain);
    }

    private static List<Rule> rulesOf(Element http) {
        List<Rule> rules = new ArrayList<>();
        NodeList urls = http.getElementsByTagName("security:intercept-url");
        for (int i = 0; i < urls.getLength(); i++) {
            Element url = (Element) urls.item(i);
            rules.add(new Rule(
                    new AntPathRequestMatcher(url.getAttribute("pattern")),
                    new WebExpressionAuthorizationManager(url.getAttribute("access"))));
        }
        return rules;
    }

    private static boolean granted(List<Rule> chain, Authentication auth, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        for (Rule rule : chain) {
            if (rule.matcher().matches(request)) {
                return rule.manager()
                        .authorize(() -> auth, new RequestAuthorizationContext(request))
                        .isGranted();
            }
        }
        // No rule matched: Spring treats the missing decision as granted. The catch-all must prevent this.
        throw new AssertionError("no intercept-url matched " + path);
    }

    private static Authentication embedGuest() {
        return new PreAuthenticatedAuthenticationToken(
                "embed-guest",
                null,
                AuthorityUtils.createAuthorityList("ROLE_EMBED_GUEST", TokenPrincipals.TOKEN_PRINCIPAL_ROLE));
    }

    private static Authentication embedAuthor() {
        return new PreAuthenticatedAuthenticationToken(
                "embed-guest",
                null,
                AuthorityUtils.createAuthorityList(
                        "ROLE_EMBED_GUEST", "ROLE_EMBED_AUTHOR", TokenPrincipals.TOKEN_PRINCIPAL_ROLE));
    }

    private static Authentication shareGuest() {
        return new PreAuthenticatedAuthenticationToken(
                "share-guest",
                null,
                AuthorityUtils.createAuthorityList("ROLE_SHARE_GUEST", TokenPrincipals.TOKEN_PRINCIPAL_ROLE));
    }

    private static Authentication scimPrincipal() {
        return new PreAuthenticatedAuthenticationToken(
                "scim:1", null, AuthorityUtils.createAuthorityList("ROLE_SCIM", TokenPrincipals.TOKEN_PRINCIPAL_ROLE));
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken("alice", "pw", AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    private static final String[] FALLBACK_PATHS = {
        "/rest/saiku/api/embed/tokens",
        "/rest/saiku/api/embed/ai/x",
        "/rest/saiku/api/embed/public/x",
        "/rest/saiku/share/foo",
        "/rest/saiku/query/abc",
        "/rest/foo",
        "/json/foo",
        "/foo",
    };

    @Test
    public void embedGuest_isDeniedOnFallbackRules() {
        for (String path : FALLBACK_PATHS) {
            assertFalse("embed guest must be denied on " + path, granted(mainChain, embedGuest(), path));
        }
        assertFalse(granted(mainChain, embedGuest(), "/rest/saiku/share/view/x"));
        assertTrue(granted(mainChain, embedGuest(), "/rest/saiku/api/embed/query/x"));
        assertTrue(granted(mainChain, embedGuest(), "/rest/saiku/api/embed/dashboard/x"));
        assertTrue(granted(mainChain, embedGuest(), "/rest/saiku/api/embed/app/x"));
        assertFalse(granted(mainChain, embedGuest(), "/rest/saiku/api/embed/authoring/x"));
    }

    @Test
    public void embedAuthor_isAdmittedOnlyOnAuthoring() {
        assertTrue(granted(mainChain, embedAuthor(), "/rest/saiku/api/embed/authoring/x"));
        assertFalse(granted(mainChain, embedAuthor(), "/rest/saiku/api/embed/tokens"));
        assertFalse(granted(mainChain, embedAuthor(), "/rest/foo"));
    }

    @Test
    public void shareGuest_isDeniedOutsideShareView() {
        assertTrue(granted(mainChain, shareGuest(), "/rest/saiku/share/view/x"));
        assertFalse(granted(mainChain, shareGuest(), "/rest/saiku/share/x"));
        assertFalse(granted(mainChain, shareGuest(), "/rest/saiku/api/embed/query/x"));
        for (String path : FALLBACK_PATHS) {
            assertFalse("share guest must be denied on " + path, granted(mainChain, shareGuest(), path));
        }
    }

    @Test
    public void scimPrincipal_isDeniedOnTheMainChain() {
        for (String path : FALLBACK_PATHS) {
            assertFalse("SCIM principal must be denied on " + path, granted(mainChain, scimPrincipal(), path));
        }
        assertFalse(granted(mainChain, scimPrincipal(), "/rest/saiku/admin/version"));
    }

    @Test
    public void guests_areDeniedOnTheXmlaChain() {
        assertFalse(granted(xmlaChain, embedGuest(), "/xmla"));
        assertFalse(granted(xmlaChain, shareGuest(), "/xmla"));
        assertTrue(granted(xmlaChain, user(), "/xmla"));
    }

    @Test
    public void realUser_isStillGrantedOnFallbacks() {
        for (String path : FALLBACK_PATHS) {
            assertTrue("a real user keeps " + path, granted(mainChain, user(), path));
        }
        assertTrue(granted(mainChain, user(), "/rest/saiku/api/embed/query/x"));
        assertTrue(granted(mainChain, user(), "/rest/saiku/share/view/x"));
        assertTrue(granted(mainChain, user(), "/rest/saiku/api/embed/authoring/x"));
    }

    @Test
    public void anonymous_isStillDeniedOnFallbacks() {
        Authentication anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertFalse(granted(mainChain, anon, "/rest/foo"));
        assertFalse(granted(mainChain, anon, "/foo"));
        assertTrue(granted(mainChain, anon, "/rest/saiku/session"));
    }
}
