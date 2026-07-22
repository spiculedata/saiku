package org.saiku.service.user;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.ISessionService;

/**
 * Coverage for {@link UserService#isAdmin()} and {@link UserService#getCurrentUserRoles()} — the
 * in-method authorization check every {@code AdminResource} endpoint gates on, and the source of
 * the {@code isadmin} flag that {@code SessionResource:153} serves on the permitAll
 * {@code /rest/saiku/session} endpoint.
 *
 * <p>saiku#1516 (1): {@code isAdmin()} returned {@code true} when the role collection was null — a
 * fail-open default in an authorization check. That default was <strong>reachable</strong>, not
 * latent. {@code getCurrentUserRolesList()} called {@code sessionService.getAllSessionObjects()}
 * once per read, and {@code SessionService} rebuilds a fresh defensive copy on every call
 * ({@code SessionService.java:236-249}), returning an empty map as soon as the principal's entry is
 * gone. {@code logout()} (:176), {@code runAs()}'s finally (:290) and {@code clearSessions()}
 * (:305) all remove it, and {@code sessionHolder} is keyed by a principal whose {@code equals()} is
 * username-based, so a second client on the same account is enough to trigger it. When the entry
 * vanished between the guard and the read, the method returned null — a ROLE_USER was granted
 * admin, and {@code getCurrentUserRoles()} threw NPE.
 *
 * <p>The fix is two layers: {@code getCurrentUserRolesList()} now snapshots the session map once
 * into a local, which removes the race outright; and {@code isAdmin()} denies on null as defence in
 * depth. Either layer alone prevents the escalation, so these tests pin the combined invariant —
 * <em>a role collection that vanishes mid-call must never yield admin</em> — rather than either
 * single line.
 *
 * <p>{@link VanishingSessionService} drives the <em>real</em> production method through the public
 * {@link UserService#setSessionService} seam. It deliberately does not subclass {@link UserService}:
 * a stub that overrides the method under test can only show that {@code isAdmin()} tolerates a null
 * it is handed, and is structurally blind to the real method <em>producing</em> one — which is
 * exactly how this race was missed the first time round.
 */
public class UserServiceTest {

    private static final List<String> ADMIN_ROLES = Collections.singletonList("ROLE_ADMIN");

    // ---- saiku#1516 (1): the race that made the fail-open default reachable ----

    @Test
    public void rolesVanishingAfterTheGuard_deniesAdmin() {
        // vanishAfter=2: the map survives the null-check and the "roles" guard, then the
        // principal's entry is evicted before the value is read — the exact interleaving a
        // concurrent logout() / clearSessions() / runAs() produces.
        VanishingSessionService session = sessionWithRoles(Collections.singletonList("ROLE_USER"));
        session.vanishAfter = 2;

        UserService us = userServiceWith(session);

        assertFalse("a ROLE_USER whose session entry is evicted mid-call must never be granted admin", us.isAdmin());
    }

    @Test
    public void rolesVanishingAfterTheGuard_getCurrentUserRolesDoesNotThrow() {
        VanishingSessionService session = sessionWithRoles(Collections.singletonList("ROLE_USER"));
        session.vanishAfter = 2;

        UserService us = userServiceWith(session);

        assertArrayEquals(
                "the snapshot must yield the roles it guarded on, not null (which NPE'd here)",
                new String[] {"ROLE_USER"},
                us.getCurrentUserRoles());
    }

    @Test
    public void rolesVanishingAfterTheGuard_preservesRolesReadBeforeTheEviction() {
        // The snapshot keeps the roles it read, so an admin stays admin. Asserted so that a future
        // change which instead yielded an empty list under the race is visible rather than silent.
        VanishingSessionService session = sessionWithRoles(Arrays.asList("ROLE_USER", "ROLE_ADMIN"));
        session.vanishAfter = 2;

        assertTrue(
                "the snapshot must preserve the roles read before the eviction",
                userServiceWith(session).isAdmin());
    }

    // ---- controls: no race. If these fail, the probes above prove nothing. ----

    @Test
    public void rolesPresentThroughout_nonAdminIsNotAdmin() {
        assertFalse(
                "ROLE_USER alone must not be admin",
                userServiceWith(sessionWithRoles(Collections.singletonList("ROLE_USER")))
                        .isAdmin());
    }

    @Test
    public void rolesPresentThroughout_adminIsAdmin() {
        assertTrue(
                "a role collection intersecting adminRoles must be admin",
                userServiceWith(sessionWithRoles(Arrays.asList("ROLE_USER", "ROLE_ADMIN")))
                        .isAdmin());
    }

    // ---- the reachable contract, which had no coverage at all ----

    @Test
    public void missingRolesEntry_deniesAdmin() {
        // The HTTP Basic shape: authenticated by Spring Security, but sessionHolder was never
        // populated (only form login calls createSession), so there is no "roles" entry.
        assertFalse(
                "a session with no roles entry must not be admin",
                userServiceWith(new VanishingSessionService()).isAdmin());
    }

    @Test
    public void emptyRoles_deniesAdmin() {
        assertFalse(
                "an empty role collection must not be admin",
                userServiceWith(sessionWithRoles(new ArrayList<>())).isAdmin());
    }

    @Test
    public void unwiredSessionService_deniesAdmin() {
        UserService us = new UserService();
        us.setAdminRoles(ADMIN_ROLES);
        // sessionService deliberately left null.
        assertFalse("an unwired session service must not grant admin", us.isAdmin());
    }

    @Test
    public void nullSessionObjects_deniesAdmin() {
        VanishingSessionService session = new VanishingSessionService();
        session.returnNull = true;

        assertFalse(
                "a null session-object map must not grant admin",
                userServiceWith(session).isAdmin());
    }

    // ---- helpers -----------------------------------------------------------

    private static UserService userServiceWith(ISessionService session) {
        UserService us = new UserService();
        us.setAdminRoles(ADMIN_ROLES);
        us.setSessionService(session);
        return us;
    }

    private static VanishingSessionService sessionWithRoles(List<String> roles) {
        VanishingSessionService session = new VanishingSessionService();
        session.objects.put("roles", roles);
        return session;
    }

    /**
     * Mirrors the real {@code SessionService.getAllSessionObjects()} contract: a <em>fresh</em>
     * defensive copy on every call, and an empty map once the principal's entry has been removed
     * ({@code SessionService.java:236-249}). {@code vanishAfter} makes the entry disappear after N
     * calls, which is what a concurrent eviction looks like to a caller that reads the map more
     * than once per operation.
     */
    private static class VanishingSessionService implements ISessionService {

        private final Map<String, Object> objects = new HashMap<>();
        private int calls = 0;
        private int vanishAfter = Integer.MAX_VALUE;
        private boolean returnNull = false;

        @Override
        public Map<String, Object> getAllSessionObjects() {
            calls++;
            if (returnNull) {
                return null;
            }
            return calls > vanishAfter ? new HashMap<>() : new HashMap<>(objects);
        }

        @Override
        public Map<String, Object> getSession() {
            return getAllSessionObjects();
        }

        @Override
        public Map<String, Object> login(HttpServletRequest req, String username, String password) {
            throw new UnsupportedOperationException("not exercised by isAdmin()");
        }

        @Override
        public void logout(HttpServletRequest req) {
            throw new UnsupportedOperationException("not exercised by isAdmin()");
        }

        @Override
        public void authenticate(HttpServletRequest req, String username, String password) {
            throw new UnsupportedOperationException("not exercised by isAdmin()");
        }

        @Override
        public void clearSessions(HttpServletRequest req, String username, String password) {
            throw new UnsupportedOperationException("not exercised by isAdmin()");
        }
    }
}
