/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.user.AccountStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** saiku#2345: a disabled user's request is refused and their session ended; a demoted admin loses admin. */
public class AccountStatusFilterTest {

    @After
    public void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, String... roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        user,
                        "pw",
                        java.util.Arrays.stream(roles)
                                .map(SimpleGrantedAuthority::new)
                                .toList()));
    }

    private static AccountStatusFilter filter(AccountStatus status, int[] revoked) {
        AccountStatusPolicy policy = new AccountStatusPolicy(n -> status, List.of("ROLE_ADMIN"), 0L);
        return new AccountStatusFilter(policy, new AccountStatusFilter.SessionHooks() {
            @Override
            public void onDisabled(HttpServletRequest req) {
                revoked[0]++;
            }

            @Override
            public void onDemoted(Authentication reduced) {
                revoked[0]++;
            }
        });
    }

    @Test
    public void disabledUser_getsA401_andTheSessionIsEnded() throws Exception {
        signIn("bob", "ROLE_USER");
        int[] revoked = {0};
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(new AccountStatus(true, false, Set.of("ROLE_USER")), revoked)
                .doFilter(new MockHttpServletRequest("GET", "/rest/saiku/api/x"), resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull("the request must not reach the application", chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(1, revoked[0]);
        assertTrue(resp.getContentAsString().contains("ACCOUNT_DISABLED"));
    }

    @Test
    public void refusalAndDemotion_areAudited_saiku2432() throws Exception {
        java.util.List<String> lines = new java.util.ArrayList<>();
        org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(AccountStatusFilter.class.getClassLoader(), false);
        org.apache.logging.log4j.core.appender.AbstractAppender appender =
                new org.apache.logging.log4j.core.appender.AbstractAppender(
                        "audit-2432", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                    @Override
                    public void append(org.apache.logging.log4j.core.LogEvent event) {
                        lines.add(event.getMessage().getFormattedMessage());
                    }
                };
        appender.start();
        ctx.getConfiguration().addAppender(appender);
        org.apache.logging.log4j.core.config.LoggerConfig lc = new org.apache.logging.log4j.core.config.LoggerConfig(
                "org.saiku.audit", org.apache.logging.log4j.Level.INFO, false);
        lc.addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
        ctx.getConfiguration().addLogger("org.saiku.audit", lc);
        ctx.updateLoggers();
        try {
            signIn("bob", "ROLE_USER");
            filter(new AccountStatus(true, false, Set.of("ROLE_USER")), new int[1])
                    .doFilter(
                            new MockHttpServletRequest("GET", "/x"),
                            new MockHttpServletResponse(),
                            new MockFilterChain());
            signIn("boss", "ROLE_USER", "ROLE_ADMIN");
            filter(new AccountStatus(true, true, Set.of("ROLE_USER")), new int[1])
                    .doFilter(
                            new MockHttpServletRequest("GET", "/x"),
                            new MockHttpServletResponse(),
                            new MockFilterChain());
        } finally {
            ctx.getConfiguration().removeLogger("org.saiku.audit");
            ctx.updateLoggers();
            appender.stop();
        }

        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("account_refused") && l.contains("bob")));
        assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("account_demoted") && l.contains("boss")));
    }

    @Test
    public void demotedAdmin_continuesWithoutAdminAuthority() throws Exception {
        signIn("boss", "ROLE_USER", "ROLE_ADMIN");
        int[] revoked = {0};
        MockFilterChain chain = new MockFilterChain();

        filter(new AccountStatus(true, true, Set.of("ROLE_USER")), revoked)
                .doFilter(
                        new MockHttpServletRequest("GET", "/rest/saiku/admin/x"), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertFalse(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertEquals("a demotion refreshes the cached session roles", 1, revoked[0]);
    }

    @Test
    public void enabledUnchangedUser_passesStraightThrough() throws Exception {
        signIn("alice", "ROLE_USER");
        int[] revoked = {0};
        MockFilterChain chain = new MockFilterChain();

        filter(AccountStatus.UNMANAGED, revoked)
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals(0, revoked[0]);
    }
}
