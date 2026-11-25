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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * saiku#2599 — the SQL workbench datasource-info / table-list endpoints
 * ({@code /rest/saiku/sql-workbench/datasources/{id}/info} and {@code .../tables}) are refused for
 * callers without {@code ROLE_ADMIN} or {@code ROLE_SQL_EXEC}, exactly like the rest of
 * {@code /sql-workbench/**}: the real {@code <intercept-url>} rules of
 * {@code applicationContext-saiku.xml} are evaluated (first match wins, as Spring does) against
 * principals of each role set. Same harness as {@link SpringUrlRulesGuestExclusionTest}.
 */
public class SqlWorkbenchUrlRulesTest {

    private static final File CONTEXT =
            new File("../../saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml");

    private record Rule(AntPathRequestMatcher matcher, WebExpressionAuthorizationManager manager) {}

    private static List<Rule> mainChain;

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
            if (!rules.isEmpty() && http.getAttribute("pattern").isEmpty() && mainChain == null) {
                mainChain = rules;
            }
        }
        assertNotNull("the main security chain was not found", mainChain);
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

    private static boolean granted(Authentication auth, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        for (Rule rule : mainChain) {
            if (rule.matcher().matches(request)) {
                return rule.manager()
                        .authorize(() -> auth, new RequestAuthorizationContext(request))
                        .isGranted();
            }
        }
        throw new AssertionError("no intercept-url matched " + path);
    }

    private static Authentication withRoles(String... roles) {
        return new UsernamePasswordAuthenticationToken("alice", "pw", AuthorityUtils.createAuthorityList(roles));
    }

    private static final String[] WORKBENCH_PATHS = {
        "/rest/saiku/sql-workbench/datasources",
        "/rest/saiku/sql-workbench/datasources/foodmart/info",
        "/rest/saiku/sql-workbench/datasources/foodmart/tables",
        "/rest/saiku/sql-workbench/query"
    };

    @Test
    public void plainUser_isDeniedOnEveryWorkbenchEndpoint() {
        for (String path : WORKBENCH_PATHS) {
            assertFalse("ROLE_USER-only caller must be denied on " + path, granted(withRoles("ROLE_USER"), path));
        }
    }

    @Test
    public void sqlExecCaller_isAdmitted() {
        for (String path : WORKBENCH_PATHS) {
            assertTrue("ROLE_SQL_EXEC caller must be admitted on " + path, granted(withRoles("ROLE_SQL_EXEC"), path));
        }
    }

    @Test
    public void adminCaller_isAdmitted() {
        for (String path : WORKBENCH_PATHS) {
            assertTrue("ROLE_ADMIN caller must be admitted on " + path, granted(withRoles("ROLE_ADMIN"), path));
        }
    }
}
