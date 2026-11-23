/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.AccountStatus;
import org.saiku.service.user.UserService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/** saiku#2345: H2 disable/demote is enforced on top of the authenticator's answer. */
public class AccountStatusPolicyTest {

    private static final List<String> ADMIN_ROLES = List.of("ROLE_ADMIN");

    private static AccountStatusPolicy policy(AccountStatus status) {
        return new AccountStatusPolicy(name -> status, ADMIN_ROLES, 0L);
    }

    private static Authentication login(String user, String... roles) {
        List<GrantedAuthority> granted = Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        return new UsernamePasswordAuthenticationToken(user, "pw", granted);
    }

    @Test
    public void disabledAccount_isDenied() {
        AccountStatusPolicy p = policy(new AccountStatus(true, false, Set.of("ROLE_USER")));

        assertNull(p.evaluate(login("bob", "ROLE_USER")));
        assertFalse(p.isAuthorised(login("bob", "ROLE_USER")));
    }

    @Test
    public void unmanagedAccount_isLeftAlone() {
        AccountStatusPolicy p = policy(AccountStatus.UNMANAGED);
        Authentication auth = login("ops", "ROLE_USER", "ROLE_ADMIN");

        assertSame(auth, p.evaluate(auth));
        assertTrue(p.isAuthorised(auth));
    }

    @Test
    public void enabledAdmin_keepsAdmin() {
        AccountStatusPolicy p = policy(new AccountStatus(true, true, Set.of("ROLE_USER", "ROLE_ADMIN")));
        Authentication auth = login("boss", "ROLE_USER", "ROLE_ADMIN");

        assertSame(auth, p.evaluate(auth));
    }

    @Test
    public void demotedAdmin_losesTheAdminAuthority_butKeepsTheRest() {
        AccountStatusPolicy p = policy(new AccountStatus(true, true, Set.of("ROLE_USER")));

        Authentication result = p.evaluate(login("boss", "ROLE_USER", "ROLE_ADMIN"));

        assertEquals(
                Set.of("ROLE_USER"),
                Set.copyOf(result.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()));
        assertEquals("boss", result.getName());
        assertTrue(result.isAuthenticated());
    }

    @Test
    public void nonCredentialAuthentications_areNotTouched() {
        AccountStatusPolicy p = policy(new AccountStatus(true, false, Set.of()));
        Authentication preAuth = new PreAuthenticatedAuthenticationToken(
                "owner", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        Authentication anon = new AnonymousAuthenticationToken(
                "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANON")));

        assertSame(preAuth, p.evaluate(preAuth));
        assertSame(anon, p.evaluate(anon));
    }

    @Test
    public void statusIsCachedBrieflyThenRefreshed() {
        int[] calls = {0};
        long[] nanos = {-5_000_000_000L}; // nanoTime may be negative; the TTL must still hold
        AccountStatusPolicy p = new AccountStatusPolicy(
                name -> {
                    calls[0]++;
                    return AccountStatus.UNMANAGED;
                },
                ADMIN_ROLES,
                25L,
                () -> nanos[0]);

        p.evaluate(login("a", "ROLE_USER"));
        nanos[0] += 24_000_000L;
        p.evaluate(login("a", "ROLE_USER"));
        assertEquals("still inside the TTL", 1, calls[0]);
        nanos[0] += 2_000_000L;
        p.evaluate(login("a", "ROLE_USER"));
        assertEquals("past the TTL", 2, calls[0]);
    }

    /**
     * saiku#2431, through the real pipeline: a row whose role array contains a duplicate (the shape
     * every console/SCIM create produces via {@code JdbcUserDAO.insert}'s prepend) is read by
     * {@link UserService#accountStatus} as found and managed, so the policy denies the disabled
     * account instead of falling back to the authenticator's answer.
     */
    @Test
    public void disabledDuplicateRoleRow_isDeniedThroughTheRealPipeline() {
        UserService us = usersOf(user("bob", false, "ROLE_USER", "ROLE_USER"));
        AccountStatusPolicy p = new AccountStatusPolicy(us::accountStatus, ADMIN_ROLES, 0L);

        assertNull(p.evaluate(login("bob", "ROLE_USER", "ROLE_ADMIN")));
        assertFalse(p.isAuthorised(login("bob", "ROLE_USER", "ROLE_ADMIN")));
    }

    /** Same pipeline, demotion: a duplicate-role row without an admin role still strips the authority. */
    @Test
    public void demotedDuplicateRoleRow_losesTheAdminAuthority() {
        UserService us = usersOf(user("boss", true, "ROLE_USER", "ROLE_USER"));
        AccountStatusPolicy p = new AccountStatusPolicy(us::accountStatus, ADMIN_ROLES, 0L);
        Authentication auth = login("boss", "ROLE_USER", "ROLE_ADMIN");

        Authentication result = p.evaluate(auth);

        assertEquals(
                Set.of("ROLE_USER"),
                Set.copyOf(result.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()));
    }

    private static SaikuUser user(String name, boolean enabled, String... roles) {
        SaikuUser u = new SaikuUser();
        u.setUsername(name);
        u.setEnabled(enabled);
        u.setRoles(roles.length == 0 ? null : roles);
        return u;
    }

    private static UserService usersOf(SaikuUser... users) {
        final List<SaikuUser> list = new ArrayList<>(Arrays.asList(users));
        return new UserService() {
            @Override
            protected List<SaikuUser> usersNamed(String username) {
                return list;
            }
        };
    }
}
