/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * saiku#1968 (CWE-863) reversion guard for {@link SecurityAwareConnectionManager#applySecurity}'s
 * fail-open no-Mondrian-role case.
 *
 * <p>Before the fix, in BOTH security modes ({@code one2one}/spring2mondrian and
 * {@code lookup}/springlookupmondrian) a caller whose Spring authorities intersected NO cube role
 * (or mapped to no Mondrian role) left {@code roleName == null}; {@code setRole(con, null, ...)}
 * then called {@code OlapConnection.setRoleName(null)}, which is Mondrian's <em>root</em> role —
 * full access to every cube and cell. There was no admin distinction, so an authenticated
 * low-privilege user whose roles resolved to nothing read ALL data.
 *
 * <p>The fix DENIES that case for a non-admin (throws {@link SaikuAccessDeniedException}) while keeping
 * the two working cases intact: a configured admin still gets full access (root role,
 * {@code setRoleName(null)}), and a non-admin WITH a matching/mapped role is still scoped to it.
 *
 * <p>These tests drive the real {@link SecurityAwareConnectionManager#applySecurity} with a hand
 * fake {@link ISaikuConnection}/{@link OlapConnection} (a JDK dynamic proxy — no Mockito) and a
 * seeded {@link SecurityContextHolder} + a real {@link UserService} carrying the configured admin
 * roles. Reverting the fix flips the two {@code denied_*} tests from green to red (pre-fix they
 * would resolve to {@code setRoleName(null)} and return the connection instead of throwing).
 */
public class SecurityAwareConnectionManagerFailClosedTest {

    private static final String DS = "foodmart";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    @Before
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- one2one / spring2mondrian --------------------------------------------------------------

    /** Non-admin, no Spring authority intersects the cube's roles -> DENIED (was: full/root). */
    @Test
    public void spring2mondrian_nonAdmin_noMatchingRole_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("SALES"); // cube offers SALES; bob has ROLE_USER
        SecurityAwareConnectionManager mgr = manager();

        try {
            mgr.applySecurity(con.saiku, spring2mondrian());
            fail("expected fail-closed deny for a non-admin that resolves to no Mondrian role");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed: access denied
        }
        assertFalse("deny must NOT set any role (never setRoleName(null)=root)", con.roleWasSet.get());
    }

    /** Admin, no matching role -> FULL (setRoleName(null)) — the critical no-regression case. */
    @Test
    public void spring2mondrian_admin_noMatchingRole_getsFull() {
        authenticateAs("root", ADMIN_ROLE);
        RoleCapturingConnection con = olapConnection("SALES"); // admin's authority doesn't name a role
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, spring2mondrian());

        assertEquals("admin keeps the same cached connection", con.saiku, result);
        assertTrue("admin should have had a role applied", con.roleWasSet.get());
        assertNull("admin -> Mondrian root role (full access)", con.role.get());
    }

    /** Non-admin WITH a matching cube role -> scoped exactly as before (unchanged). */
    @Test
    public void spring2mondrian_nonAdmin_matchingRole_scoped() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("ROLE_USER", "SALES"); // ROLE_USER matches
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, spring2mondrian());

        assertEquals(con.saiku, result);
        assertTrue(con.roleWasSet.get());
        assertEquals("non-admin scoped to the matched Mondrian role", "ROLE_USER", con.role.get());
    }

    // ---- lookup / springlookupmondrian ----------------------------------------------------------

    /** Non-admin, no authority maps to a Mondrian role -> DENIED (was: full/root). */
    @Test
    public void springlookup_nonAdmin_noMapping_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();
        // Mapping only covers ROLE_ADMIN; bob's ROLE_USER maps to nothing.
        SaikuDatasource ds = springlookup("ROLE_ADMIN=salesrole");

        try {
            mgr.applySecurity(con.saiku, ds);
            fail("expected fail-closed deny for a non-admin whose roles map to no Mondrian role");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed: access denied
        }
        assertFalse(con.roleWasSet.get());
    }

    /** Admin, no mapping -> FULL (setRoleName(null)) — no-regression for the lookup mode too. */
    @Test
    public void springlookup_admin_noMapping_getsFull() {
        authenticateAs("root", ADMIN_ROLE);
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();
        // Deliberately no mapping entry for ROLE_ADMIN, so the admin resolves to no role.
        SaikuDatasource ds = springlookup("ROLE_USER=salesrole");

        ISaikuConnection result = mgr.applySecurity(con.saiku, ds);

        assertEquals(con.saiku, result);
        assertTrue(con.roleWasSet.get());
        assertNull("admin -> Mondrian root role (full access)", con.role.get());
    }

    /** Non-admin WITH a mapped Mondrian role -> scoped exactly as before (unchanged). */
    @Test
    public void springlookup_nonAdmin_mappedRole_scoped() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();
        SaikuDatasource ds = springlookup("ROLE_USER=salesrole");

        ISaikuConnection result = mgr.applySecurity(con.saiku, ds);

        assertEquals(con.saiku, result);
        assertTrue(con.roleWasSet.get());
        assertEquals("non-admin scoped to the mapped Mondrian role", "salesrole", con.role.get());
    }

    /**
     * saiku#779: a mapping to a Mondrian role the schema doesn't declare (a typo, or a role renamed
     * in the schema) makes setRoleName throw. applySecurity used to log that and return the
     * connection with no role applied — Mondrian root, full access. It must deny instead.
     */
    @Test
    public void springlookup_nonAdmin_mappedToUndeclaredRole_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("salesrole");
        SaikuDatasource ds = springlookup("ROLE_USER=Typo");

        try {
            manager().applySecurity(con.saiku, ds);
            fail("a role that can't be applied must deny, not fall through to Mondrian root");
        } catch (SaikuAccessDeniedException expected) {
            assertFalse("no role was applied", con.roleWasSet.get());
        }
    }

    /** Same for an admin: a resolved role that can't be applied is a misconfiguration, not root. */
    @Test
    public void springlookup_admin_mappedToUndeclaredRole_denied() {
        authenticateAs("root", ADMIN_ROLE);
        RoleCapturingConnection con = olapConnection("salesrole");
        SaikuDatasource ds = springlookup(ADMIN_ROLE + "=Typo");

        try {
            manager().applySecurity(con.saiku, ds);
            fail("a role that can't be applied must deny, even for an admin");
        } catch (SaikuAccessDeniedException expected) {
            assertFalse("no role was applied", con.roleWasSet.get());
        }
    }

    // ---- fail-closed-on-ambiguity ---------------------------------------------------------------

    /** No UserService injected -> admin cannot be proven -> DENY (never default-to-full). */
    @Test
    public void spring2mondrian_noUserService_treatedAsNonAdmin_denied() {
        authenticateAs("root", ADMIN_ROLE); // would be admin IF admin roles were resolvable
        RoleCapturingConnection con = olapConnection("SALES");
        SecurityAwareConnectionManager mgr = new SecurityAwareConnectionManager(); // no userService

        try {
            mgr.applySecurity(con.saiku, spring2mondrian());
            fail("with no admin-role source, admin cannot be proven -> must fail closed (deny)");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed on ambiguity
        }
        assertFalse(con.roleWasSet.get());
    }

    /**
     * saiku#1968 F1-hardening: a LIVE request (HTTP request attributes present) that reaches the
     * guard with NO principal must FAIL CLOSED — not fall through to Mondrian root. This is the
     * shape the async-worker exploit had before SecurityContext propagation landed (no principal on
     * the worker), and any future context-loss bug. INVERTED from the old
     * {@code noPrincipal_notDenied} test, which pinned the fall-open behaviour.
     */
    @Test
    public void spring2mondrian_requestInFlightNoPrincipal_denied() {
        // No authentication (cleared by @Before) but a request IS in flight.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            RoleCapturingConnection con = olapConnection("SALES");
            SecurityAwareConnectionManager mgr = manager();
            try {
                mgr.applySecurity(con.saiku, spring2mondrian());
                fail("a live request with no principal must fail closed, not get Mondrian root");
            } catch (SaikuAccessDeniedException expected) {
                // fail-closed
            }
            assertFalse(con.roleWasSet.get());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * Genuine start-up / connection warm-up: no principal AND no HTTP request in flight -> the
     * guard does NOT throw (nothing is served, and the per-request applySecurity enforces when a
     * real caller arrives). Preserves start-up; the narrow exemption only fires here.
     */
    @Test
    public void spring2mondrian_noPrincipalNoRequest_notDenied() {
        RequestContextHolder.resetRequestAttributes(); // ensure truly context-free
        RoleCapturingConnection con = olapConnection("SALES");
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, spring2mondrian());

        assertEquals("context-free start-up -> preserve prior (non-serving) behaviour", con.saiku, result);
    }

    // ---- saiku#1972: present-but-invalid role names -------------------------------------------

    /** A blank mapping value ({@code ROLE_USER= }) is treated as unmapped -> non-admin DENIED. */
    @Test
    public void springlookup_nonAdmin_blankMappingValue_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();

        try {
            mgr.applySecurity(con.saiku, springlookup("ROLE_USER= "));
            fail("a blank mapped role must fold into the no-role deny path, not reach Mondrian root");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed
        }
        assertFalse(con.roleWasSet.get());
    }

    /** A blank mapping value alongside a real one is skipped; the real role still applies. */
    @Test
    public void springlookup_nonAdmin_blankAndRealMapping_scopedToReal() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, springlookup("ROLE_USER= ;ROLE_USER=salesrole"));

        assertEquals(con.saiku, result);
        assertEquals("salesrole", con.role.get());
    }

    /**
     * A mapping to a role the schema doesn't define (typo, trailing whitespace, removed role) makes
     * Mondrian's setRoleName throw. That used to be swallowed, leaving the connection at root.
     */
    @Test
    public void springlookup_nonAdmin_unknownMondrianRole_denied() {
        for (String mapping : new String[] {"ROLE_USER=salesrol", "ROLE_USER=salesrole "}) {
            authenticateAs("bob", "ROLE_USER");
            RoleCapturingConnection con = olapConnection("salesrole");
            SecurityAwareConnectionManager mgr = manager();

            try {
                mgr.applySecurity(con.saiku, springlookup(mapping));
                fail("unknown Mondrian role [" + mapping + "] must deny, not leave the connection at root");
            } catch (SaikuAccessDeniedException expected) {
                // fail-closed
            }
            assertFalse(con.roleWasSet.get());
        }
    }

    /**
     * A role-application failure denies an admin too: the resolved role could not be applied, so the
     * connection's role is not what the configuration asked for. Surfacing the error beats silently
     * widening to root.
     */
    @Test
    public void springlookup_admin_unknownMondrianRole_denied() {
        authenticateAs("root", ADMIN_ROLE);
        RoleCapturingConnection con = olapConnection("salesrole");
        SecurityAwareConnectionManager mgr = manager();

        try {
            mgr.applySecurity(con.saiku, springlookup(ADMIN_ROLE + "=nosuchrole"));
            fail("a failed role application must not fall back to root");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed
        }
    }

    /** security.enabled=true with an unrecognised security.type -> non-admin DENIED. */
    @Test
    public void unknownSecurityType_nonAdmin_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("ROLE_USER");
        SecurityAwareConnectionManager mgr = manager();

        try {
            mgr.applySecurity(con.saiku, securityEnabledWithType("one-to-one"));
            fail("an unrecognised security.type must deny a non-admin, not leave the connection at root");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed
        }
        assertFalse(con.roleWasSet.get());
    }

    /** security.enabled=true with no security.type at all -> non-admin DENIED. */
    @Test
    public void missingSecurityType_nonAdmin_denied() {
        authenticateAs("bob", "ROLE_USER");
        RoleCapturingConnection con = olapConnection("ROLE_USER");
        SecurityAwareConnectionManager mgr = manager();

        try {
            mgr.applySecurity(con.saiku, securityEnabledWithType(null));
            fail("a missing security.type must deny a non-admin, not leave the connection at root");
        } catch (SaikuAccessDeniedException expected) {
            // fail-closed
        }
        assertFalse(con.roleWasSet.get());
    }

    /** Unrecognised security.type keeps admin at full access, as with the no-role case. */
    @Test
    public void unknownSecurityType_admin_getsFull() {
        authenticateAs("root", ADMIN_ROLE);
        RoleCapturingConnection con = olapConnection("ROLE_USER");
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, securityEnabledWithType("bogus"));

        assertEquals(con.saiku, result);
    }

    /** Unrecognised security.type during context-free start-up does not throw. */
    @Test
    public void unknownSecurityType_noPrincipalNoRequest_notDenied() {
        RequestContextHolder.resetRequestAttributes();
        RoleCapturingConnection con = olapConnection("ROLE_USER");
        SecurityAwareConnectionManager mgr = manager();

        ISaikuConnection result = mgr.applySecurity(con.saiku, securityEnabledWithType("bogus"));

        assertEquals(con.saiku, result);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static SecurityAwareConnectionManager manager() {
        SecurityAwareConnectionManager mgr = new SecurityAwareConnectionManager();
        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList(ADMIN_ROLE));
        mgr.setUserService(us);
        return mgr;
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    private static SaikuDatasource spring2mondrian() {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE);
        return new SaikuDatasource(DS, SaikuDatasource.Type.OLAP, props);
    }

    private static SaikuDatasource springlookup(String mapping) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        props.setProperty(
                ISaikuConnection.SECURITY_TYPE_KEY, ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE);
        props.setProperty(ISaikuConnection.SECURITY_LOOKUP_KEY, mapping);
        return new SaikuDatasource(DS, SaikuDatasource.Type.OLAP, props);
    }

    private static SaikuDatasource securityEnabledWithType(String type) {
        Properties props = new Properties();
        props.setProperty(ISaikuConnection.SECURITY_ENABLED_KEY, "true");
        if (type != null) {
            props.setProperty(ISaikuConnection.SECURITY_TYPE_KEY, type);
        }
        return new SaikuDatasource(DS, SaikuDatasource.Type.OLAP, props);
    }

    private static RoleCapturingConnection olapConnection(String... availableRoles) {
        return new RoleCapturingConnection(Arrays.asList(availableRoles));
    }

    /**
     * Hand fake pairing an {@link ISaikuConnection} with a JDK-proxy {@link OlapConnection} that
     * records {@code setRoleName(...)} and answers {@code getAvailableRoleNames()}. No Mockito.
     */
    private static final class RoleCapturingConnection {
        final AtomicReference<String> role = new AtomicReference<>();
        final AtomicBoolean roleWasSet = new AtomicBoolean(false);
        final OlapConnection olap;
        final ISaikuConnection saiku;

        RoleCapturingConnection(List<String> availableRoles) {
            this.olap = (OlapConnection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {OlapConnection.class},
                    new OlapHandler(availableRoles));
            this.saiku = (ISaikuConnection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {ISaikuConnection.class}, new SaikuHandler());
        }

        /** Handles the only two OlapConnection methods applySecurity/setRole touch. */
        private final class OlapHandler implements InvocationHandler {
            private final List<String> availableRoles;

            OlapHandler(List<String> availableRoles) {
                this.availableRoles = availableRoles;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws OlapException {
                switch (method.getName()) {
                    case "getAvailableRoleNames":
                        return availableRoles;
                    case "setRoleName":
                        String requested = args == null ? null : (String) args[0];
                        if (requested != null && !availableRoles.contains(requested)) {
                            // Mirrors MondrianOlap4jConnection.setRoleName on a role the schema lacks.
                            throw new OlapException("Unknown role '" + requested + "'");
                        }
                        roleWasSet.set(true);
                        role.set(args == null ? null : (String) args[0]);
                        return null;
                    case "isWrapperFor":
                        return false; // not a RolapConnection -> setRole takes the setRoleName branch
                    case "toString":
                        return "FakeOlapConnection";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (args == null ? null : args[0]);
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        }

        /** Handles the ISaikuConnection methods getConnectionRoles/setRole read. */
        private final class SaikuHandler implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                    case "getDatasourceType":
                        return ISaikuConnection.OLAP_DATASOURCE;
                    case "getConnection":
                        return olap;
                    case "getName":
                        return DS;
                    case "toString":
                        return "FakeSaikuConnection";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (args == null ? null : args[0]);
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    /** Guards that the fake OlapConnection is really an OlapConnection (proxy instanceof). */
    @Test
    public void fake_isOlapConnection() {
        Connection c = olapConnection("SALES").olap;
        assertTrue(c instanceof OlapConnection);
    }
}
