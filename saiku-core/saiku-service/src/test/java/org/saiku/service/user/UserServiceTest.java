/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.user;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Coverage for {@link UserService#isAdmin()} and {@link UserService#getCurrentUserRoles()} — the
 * in-method authorization check every {@code AdminResource} endpoint gates on, and the source of the
 * {@code isadmin} flag {@code SessionResource:153} serves on the permitAll {@code /rest/saiku/session}
 * endpoint.
 *
 * <p>saiku#1732: the role read is now AUTHORITATIVE on Spring's {@link SecurityContextHolder} rather
 * than the lazily-seeded session {@code "roles"} map. The session map was populated only
 * conditionally (form-login / runAs), so under stateless Basic auth it was frequently empty and
 * {@code isAdmin()} non-deterministically 403'd the admin console across JVM launches. The session
 * value was ALWAYS derived from {@code SecurityContextHolder} authorities (there is no session-only
 * role writer), so reading the holder directly is superset-or-equal and always fresh — it can only
 * make the read more accurate, never grant an unheld role. These tests drive the real method through
 * the holder and pin the fail-closed contract (saiku#1516): absent / unauthenticated / anonymous
 * auth must never yield admin.
 */
public class UserServiceTest {

    private static final List<String> ADMIN_ROLES = Collections.singletonList("ROLE_ADMIN");

    @After
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- the admin decision reads the authenticated authorities ----

    @Test
    public void adminAuthority_isAdmin() {
        authenticateWith("ROLE_USER", "ROLE_ADMIN");
        assertTrue("a principal holding ROLE_ADMIN must be admin", userService().isAdmin());
    }

    @Test
    public void nonAdminAuthority_isNotAdmin() {
        // No loosening: a ROLE_USER-only principal never intersects adminRoles.
        authenticateWith("ROLE_USER");
        assertFalse("ROLE_USER alone must not be admin", userService().isAdmin());
    }

    @Test
    public void getCurrentUserRoles_returnsTheAuthoritySet() {
        authenticateWith("ROLE_USER", "ROLE_ADMIN");
        String[] roles = userService().getCurrentUserRoles();
        Arrays.sort(roles);
        assertArrayEquals(
                "getCurrentUserRoles must surface the authenticated authorities verbatim",
                new String[] {"ROLE_ADMIN", "ROLE_USER"},
                roles);
    }

    // ---- fail-closed contract (saiku#1516 regression stays locked) ----

    @Test
    public void noAuthentication_deniesAdmin() {
        SecurityContextHolder.clearContext();
        assertFalse("no authentication must not grant admin", userService().isAdmin());
        assertArrayEquals(
                "no authentication must yield an empty role list, not null",
                new String[0],
                userService().getCurrentUserRoles());
    }

    @Test
    public void anonymousToken_deniesAdmin() {
        // SEC saiku#1732: an anonymous principal surfaces ROLE_ANONYMOUS from authorities where the
        // session gave empty; ROLE_ANONYMOUS is never an admin/schema role, and we exclude the
        // anonymous token explicitly, so isAdmin() stays false and no role is contributed.
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertFalse("an anonymous principal must not be admin", userService().isAdmin());
        assertArrayEquals(
                "an anonymous principal must contribute no roles",
                new String[0],
                userService().getCurrentUserRoles());
    }

    @Test
    public void shareOrEmbedGuest_isNotAdmin_saiku1732() {
        // SEC saiku#1732: a share/embed guest is a real authenticated principal carrying a guest
        // role (e.g. ROLE_SHARE_GUEST / ROLE_EMBED_GUEST). Post-fix it surfaces that guest role from
        // authorities where the session gave empty — harmless (guest roles aren't admin/Mondrian
        // roles, and guest data runs via runAs(owner roles)), but lock isAdmin()==false so it's
        // proven, not assumed.
        authenticateWith("ROLE_SHARE_GUEST");
        assertFalse("a share guest must not be admin", userService().isAdmin());
        authenticateWith("ROLE_EMBED_GUEST");
        assertFalse("an embed guest must not be admin", userService().isAdmin());
    }

    @Test
    public void emptyAuthorities_deniesAdmin() {
        authenticateWith(/* no authorities */ );
        assertFalse(
                "an authenticated principal with no authorities must not be admin",
                userService().isAdmin());
    }

    // ---- saiku#1809 PR4: isEnabled(username) reflects USERS.ENABLED, fail-closed ----

    @Test
    public void isEnabled_trueForEnabledUser() {
        UserService us = usersOf(user("alice", true), user("bob", true));
        assertTrue(us.isEnabled("alice"));
    }

    /**
     * saiku#1907 F4 (CWE-178): a job owned by an admin-typed mixed-case name must still resolve
     * its enabled-state (else the scheduler fail-closes and auto-disables a legitimately-owned
     * job). RED pre-fix (case-sensitive equals denies the case-variant lookup).
     */
    @Test
    public void isEnabled_matchesCaseInsensitively() {
        UserService us = usersOf(user("alice", true));
        assertTrue("a case-variant username must still resolve enabled state", us.isEnabled("Alice"));
    }

    @Test
    public void isEnabled_falseForDisabledUser() {
        UserService us = usersOf(user("alice", false));
        assertFalse("a disabled account must report not-enabled", us.isEnabled("alice"));
    }

    @Test
    public void isEnabled_failsClosedForUnknownOrBlank() {
        UserService us = usersOf(user("alice", true));
        assertFalse("unknown username -> false (fail-closed)", us.isEnabled("nobody"));
        assertFalse("blank username -> false (fail-closed)", us.isEnabled("   "));
        assertFalse("null username -> false (fail-closed)", us.isEnabled(null));
    }

    @Test
    public void isEnabled_failsClosedOnLookupError() {
        UserService us = new UserService() {
            @Override
            public List<SaikuUser> getUsers() {
                throw new RuntimeException("db down");
            }
        };
        assertFalse("a lookup error must report not-enabled (fail-closed)", us.isEnabled("alice"));
    }

    private static SaikuUser user(String name, boolean enabled) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        return u;
    }

    private static UserService usersOf(SaikuUser... users) {
        final List<SaikuUser> list = new ArrayList<>(Arrays.asList(users));
        return new UserService() {
            @Override
            public List<SaikuUser> getUsers() {
                return list;
            }
        };
    }

    // ---- helpers -----------------------------------------------------------

    private static UserService userService() {
        UserService us = new UserService();
        us.setAdminRoles(ADMIN_ROLES);
        // sessionService intentionally left null — the role read no longer touches it (saiku#1732).
        return us;
    }

    private static void authenticateWith(String... authorities) {
        List<SimpleGrantedAuthority> granted =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("u", "p", granted));
    }
}
