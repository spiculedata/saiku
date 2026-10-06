/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.core;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.sql.Connection;
import java.util.*;
import mondrian.olap4j.SaikuMondrianHelper;
import org.apache.commons.lang3.StringUtils;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.saiku.datasources.connection.AbstractConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuConnectionFactory;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.ISessionService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

public class SecurityAwareConnectionManager extends AbstractConnectionManager implements Serializable {

    /**
     * serialisation UID
     */
    private static final long serialVersionUID = -5912836681963684201L;

    /**
     * saiku#1970 (CWE-178) — the field separator used by {@link #connectionCacheKey(String, String)}.
     *
     * <p>ASCII UNIT SEPARATOR (U+001F): a C0 control character that is not a legal character in a
     * datasource name or in a login, so it cannot occur inside either field of the key.
     */
    static final String KEY_FIELD_SEPARATOR = "\u001F";

    /**
     * saiku#1970 (CWE-178) — an <b>injective</b> composite key for a security-enabled datasource's
     * cached connection: it pairs the datasource {@code name} with the caller's identity so the two
     * fields can never be mistaken for one another.
     *
     * <p>The historical key was {@code name + "-" + identity}. Both fields are admin-chosen and share
     * one flat namespace, so a literal {@code "-"} lets two genuinely different (datasource, user)
     * pairs collapse onto the same key: datasource {@code foo} + user {@code bar-x} and datasource
     * {@code foo-bar} + user {@code x} both produced {@code "foo-bar-x"}, and the second caller was
     * handed a live connection built for the <em>other</em> datasource — with the other datasource's
     * role already applied.
     *
     * <p>Rather than guess which characters are impossible in an admin-chosen name, the encoding is
     * <b>length-prefixed</b>: {@code <len(name)> ":" <name> <SEP> <identity>}. The length prefix
     * alone makes the mapping injective for <em>arbitrary</em> strings — the name's boundary is fixed
     * before the identity is read, so no byte of either field can shift it and no choice of
     * name/username can forge another pair's key. The separator is kept purely for legibility; the
     * key still reads as {@code 8:foodmart<U+001F>bob} in a log line or a debugger.
     *
     * <p>Null-safe: a null field is treated as the empty string, so {@code (null, "x")} and
     * {@code ("", "x")} share a key rather than one of them producing {@code "null:x"}.
     */
    static String connectionCacheKey(String name, String identity) {
        String ds = (name == null) ? "" : name;
        String user = (identity == null) ? "" : identity;
        return ds.length() + ":" + ds + KEY_FIELD_SEPARATOR + user;
    }

    private transient Map<String, ISaikuConnection> connections = new HashMap<>();

    private final List<String> errorConnections = new ArrayList<>();

    private ISessionService sessionService;

    public void setSessionService(ISessionService ss) {
        this.sessionService = ss;
    }

    private static final Logger log = LoggerFactory.getLogger(SecurityAwareConnectionManager.class);

    @Override
    public void init() {
        try {
            this.connections = getAllConnections();
        } catch (SaikuOlapException e) {
            log.error("Error getting connections", e);
        }
    }

    @Override
    public void destroy() {
        if (connections != null && !connections.isEmpty()) {
            for (ISaikuConnection con : connections.values()) {
                try {
                    Connection c = con.getConnection();
                    if (!c.isClosed()) {
                        c.close();
                    }
                } catch (Exception e) {
                    log.error("Error destroying connections", e);
                }
            }
        }
        if (connections != null) {
            connections.clear();
        }
    }

    @Override
    protected ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource) {

        ISaikuConnection con = null;
        if (isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE)
                && sessionService != null) {
            datasource = handlePassThrough(datasource);
        }

        String newName = resolveConnectionKey(name, datasource);

        // saiku#1969: the cache OWNS these connections. Anything that closes one behind the cache's
        // back (the XMLA fork's unconditional close() on a shared connection is the historical
        // case) leaves a closed instance in the map that every later caller would be handed, with
        // no health check in between — so subsequent queries on that datasource fail until an admin
        // refresh. Evict a closed OLAP connection and rebuild it instead of serving it.
        if (connections.containsKey(newName) && isClosedOlapConnection(connections.get(newName))) {
            log.warn("saiku#1969: cached OLAP connection \"{}\" is closed — evicting and reconnecting", newName);
            connections.remove(newName);
        }

        if (!connections.containsKey(newName)) {
            con = connect(name, datasource);
            if (con != null) {
                connections.put(newName, con);
            } else {
                if (!errorConnections.contains(newName)) {
                    errorConnections.add(newName);
                }
            }

        } else {
            con = connections.get(newName);
        }
        if (con != null && !isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE)) {
            con = applySecurity(con, datasource);
        }
        return con;
    }

    @Override
    protected ISaikuConnection refreshInternalConnection(String name, SaikuDatasource datasource) {
        try {
            String newName = resolveConnectionKey(name, datasource);

            ISaikuConnection con = connections.remove(newName);
            if (con != null) {
                con.clearCache();
            }
            return getInternalConnection(name, datasource);
        } catch (Exception e) {
            log.error("Error refreshing connection: " + name, e);
        }
        return null;
    }

    /**
     * Whether a cached connection wraps an {@link OlapConnection} that has been closed underneath
     * the cache (saiku#1969).
     *
     * <p>Only OLAP connections are health-checked: a non-OLAP (JDBC/legacy) connection's lifecycle
     * is not what this issue is about, and an unrecognised connection type is treated as usable
     * rather than silently rebuilt. A health check that itself fails is also treated as "can't
     * prove it's dead" — we hand it out and let the caller fail, exactly as before this change.
     *
     * <p>Package-private so the reversion guard can assert the check directly.
     */
    boolean isClosedOlapConnection(ISaikuConnection con) {
        if (con == null) {
            return false;
        }
        try {
            if (con.getConnection() instanceof OlapConnection) {
                return ((OlapConnection) con.getConnection()).isClosed();
            }
        } catch (Exception e) {
            log.debug("Could not determine closed state of cached connection {}", con.getName(), e);
        }
        return false;
    }

    /**
     * Compute the cache key under which a security-enabled datasource's connection is stored, so
     * that a per-user connection (and therefore a per-user Mondrian role, applied by {@link
     * #applySecurity}) is isolated to that user.
     *
     * <p>saiku#1948 (F1, CWE-863) — the shared-connection role race. The historical rule keyed the
     * connection {@code name + "-" + username} ONLY when the session map carries a {@code
     * "username"}, and that entry is populated exclusively by the UI {@code /session} login. A pure
     * XMLA client (HTTP Basic against the stateless {@code /xmla/**} chain, no UI login) has no such
     * session entry, so every authenticated XMLA caller collapsed onto the SAME bare-{@code name}
     * cached connection. {@code applySecurity} then mutates that one shared connection's role on
     * every request, and Mondrian reads the role live during query evaluation — so a concurrent
     * request could flip another user's in-flight XMLA query to a different role scope (an admin
     * request widening a non-admin's still-running query to root), for the whole query duration.
     *
     * <p>Fix: when there is no session {@code "username"} but there IS a fully-authenticated,
     * non-anonymous principal, key per that principal instead. Each XMLA user then gets their own
     * cached connection and their own {@code setRoleName}, and the race is gone.
     *
     * <p>The REST path is unchanged: a UI session always carries {@code "username"}, so it takes the
     * first branch exactly as before. The principal fallback only engages for a security-enabled
     * datasource reached without a session username (i.e. the XMLA/Basic case). A security-DISABLED
     * datasource still resolves to the bare {@code name} for everyone — {@code applySecurity} never
     * sets a role there, so there is nothing to isolate.
     *
     * <p>Package-private so the reversion guard can assert the keying directly.
     */
    String resolveConnectionKey(String name, SaikuDatasource datasource) {
        if (isDatasourceSecurityEnabled(datasource) && sessionService != null) {
            Map<String, Object> session = sessionService.getAllSessionObjects();
            String username = session == null ? null : (String) session.get("username");
            if (username != null) {
                return connectionCacheKey(name, Usernames.canonicalize(username));
            }
            // saiku#1970: the session "username" is the #1907-canonical (lower-cased) identity, so
            // the principal fallback is canonicalised the same way — otherwise the SAME user
            // reaching the SAME datasource via XMLA ("JSmith") and via the UI ("jsmith") got two
            // separate cached connections. Cosmetic (both slots are scoped to that user's own
            // authorities, so neither can carry another user's role), but the duplicates are
            // avoidable and the canonical form is the single identity Saiku compares on elsewhere.
            String principal = currentPrincipalName();
            if (principal != null) {
                return connectionCacheKey(name, Usernames.canonicalize(principal));
            }
        }
        return name;
    }

    /**
     * The name of the current fully-authenticated, non-anonymous principal from the {@link
     * SecurityContextHolder}, or {@code null} if there is none. Mirrors the null-guarding style of
     * {@link #getSpringRoles()}.
     */
    private String currentPrincipalName() {
        if (SecurityContextHolder.getContext() == null) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String principalName = auth.getName();
        return (principalName != null && !principalName.isEmpty()) ? principalName : null;
    }

    private SaikuDatasource handlePassThrough(SaikuDatasource datasource) {

        Map<String, Object> session = sessionService.getAllSessionObjects();
        // saiku#1907 F3: pass-through forwards the user's login to the warehouse as its
        // credentials, so it must use the ORIGINAL store spelling ("principal"), NOT the
        // canonicalised (lower-cased) ACL/home identity ("username") — a case-sensitive
        // warehouse account (e.g. JSmith) would otherwise fail to authenticate. Fall back to
        // "username" for sessions minted before this split (and delegated runAs sessions).
        String username = (String) session.get("principal");
        if (username == null) {
            username = (String) session.get("username");
        }

        if (username != null) {
            String password = (String) session.get("password");
            datasource.getProperties().setProperty("username", username);
            if (password != null) {
                datasource.getProperties().setProperty("password", password);
            }
            return datasource;
        }

        return null;
    }

    /**
     * Applies the caller's Mondrian role to a security-enabled datasource's connection.
     *
     * <p>Package-private so the saiku#1968 reversion guard can drive the fail-closed no-match branch
     * directly (mirrors {@link #resolveConnectionKey}).
     */
    ISaikuConnection applySecurity(ISaikuConnection con, SaikuDatasource datasource) {
        if (con == null) {
            throw new IllegalArgumentException("Cannot apply Security to NULL connection object");
        }

        if (isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_SPRING2MONDRIAN_VALUE)) {
            List<String> springRoles = getSpringRoles();
            List<String> conRoles = getConnectionRoles(con);
            String roleName = null;

            for (String sprRole : springRoles) {
                if (conRoles.contains(sprRole)) {
                    if (roleName == null) {
                        roleName = sprRole;
                    } else {
                        roleName += "," + sprRole;
                    }
                }
            }

            if (StringUtils.isBlank(roleName)) {
                // saiku#1968 (CWE-863): no Spring authority intersected the cube's roles. Deny a
                // non-admin instead of falling through to setRoleName(null) = Mondrian root.
                // saiku#1972: a blank name is no role too.
                roleName = null;
                enforceRoleResolvedOrAdmin(datasource);
            }

            if (setRole(con, roleName, datasource)) {
                return con;
            }

        } else if (isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_SPRINGLOOKUPMONDRIAN_VALUE)) {
            Map<String, List<String>> mapping = getRoleMapping(datasource);
            List<String> springRoles = getSpringRoles();
            String roleName = null;
            for (String sprRole : springRoles) {
                if (mapping.containsKey(sprRole)) {
                    List<String> roles = mapping.get(sprRole);
                    for (String role : roles) {
                        if (roleName == null) {
                            roleName = role;
                        } else {
                            roleName += "," + role;
                        }
                    }
                }
            }

            if (StringUtils.isBlank(roleName)) {
                // saiku#1968 (CWE-863): no authority mapped to a Mondrian role. Deny a non-admin
                // instead of falling through to setRoleName(null) = Mondrian root.
                // saiku#1972: a blank name is no role too.
                roleName = null;
                enforceRoleResolvedOrAdmin(datasource);
            }

            if (setRole(con, roleName, datasource)) {
                return con;
            }

        } else if (isDatasourceSecurityEnabled(datasource)
                && !isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE)) {
            // saiku#1972 (CWE-863): security is on but security.type is missing or unrecognised, so
            // neither branch above sets a role and the connection would stay at Mondrian root. Treat
            // it as "no role resolved": admin keeps full access, anyone else is denied.
            log.warn(
                    "saiku#1972: datasource \"{}\" has {}=true but an unrecognised {} \"{}\"; "
                            + "no Mondrian role can be applied.",
                    datasource.getName(),
                    ISaikuConnection.SECURITY_ENABLED_KEY,
                    ISaikuConnection.SECURITY_TYPE_KEY,
                    datasource.getProperties().getProperty(ISaikuConnection.SECURITY_TYPE_KEY));
            enforceRoleResolvedOrAdmin(datasource);
        }

        return con;
    }

    /**
     * saiku#1968 (CWE-863) — fail-closed guard for the no-Mondrian-role case.
     *
     * <p>On a security-enabled datasource {@link #applySecurity} resolves the caller's Spring
     * authorities to a Mondrian role; if NOTHING resolves, {@code roleName} is left {@code null} and
     * {@code setRoleName(null)} would hand the caller Mondrian's <em>root</em> role — full access to
     * every cube and cell. Historically that fell OPEN for every authenticated user, so a
     * low-privilege user whose roles mapped to nothing could read all data (REST and XMLA).
     *
     * <p>We now permit the null role (full access) ONLY for a configured admin, determined from the
     * deployment's admin-role list ({@link UserService#getAdminRoles()}) — never a hardcoded role
     * name. Any other authenticated caller is DENIED with an access-denied exception.
     *
     * <p><b>Fail-closed on ambiguity:</b> if the admin roles are unavailable ({@code userService}
     * null, or a null/empty list) or the admin check throws, the caller is treated as NON-admin and
     * denied — never defaulted to full.
     *
     * <p>The exemption for "no authenticated principal" is deliberately NARROW: it applies ONLY when
     * there is also no HTTP request in flight ({@link RequestContextHolder#getRequestAttributes()}
     * is {@code null}) — i.e. genuine server start-up / background connection warm-up, where no data
     * is being served to anyone and the cached connection's role is re-applied on every subsequent
     * request. A LIVE request that reaches here with no principal (e.g. the async worker before
     * saiku#1968's SecurityContext propagation, or any future context-loss bug) FAILS CLOSED rather
     * than falling through to Mondrian root. Anonymous access is already blocked upstream
     * (saiku#1905).
     */
    private void enforceRoleResolvedOrAdmin(SaikuDatasource datasource) {
        String principal = currentPrincipalName();
        if (principal == null) {
            // No authenticated principal. Exempt ONLY the genuinely context-free case (start-up /
            // warm-up, no request in flight); a live request without a principal fails closed.
            if (RequestContextHolder.getRequestAttributes() == null) {
                return;
            }
        } else if (isCurrentUserAdmin()) {
            return; // configured admin keeps full access (Mondrian root role)
        }
        String ds = datasource == null ? "?" : datasource.getName();
        log.warn(
                "saiku#1968: denying connection on security-enabled datasource \"{}\" — caller "
                        + "(principal \"{}\") resolved to no Mondrian role and is not an admin (fail-closed).",
                ds,
                principal);
        throw new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"" + ds + "\".");
    }

    /**
     * Whether the current caller is a configured admin — i.e. one of their Spring authorities is
     * named in {@link UserService#getAdminRoles()}. Fail-closed: any missing dependency or error
     * yields {@code false} (treat as non-admin), never a default-true.
     */
    private boolean isCurrentUserAdmin() {
        try {
            UserService us = getUserService();
            if (us == null) {
                return false;
            }
            List<String> adminRoles = us.getAdminRoles();
            if (adminRoles == null || adminRoles.isEmpty()) {
                return false;
            }
            for (String sprRole : getSpringRoles()) {
                if (adminRoles.contains(sprRole)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("saiku#1968: admin-role resolution failed; treating caller as non-admin (deny).", e);
            return false;
        }
    }

    /**
     * Applies {@code roleName} to the connection.
     *
     * <p>saiku#1972 (CWE-863): if Mondrian rejects the role (a mapping typo, trailing whitespace, a
     * role removed from the schema), the connection's role is not what the configuration asked for
     * — on a fresh per-principal connection it is still Mondrian root. That used to be logged and
     * swallowed, handing the caller full access; it now fails closed with an access-denied
     * exception, for admins too, so the misconfiguration surfaces instead of silently widening.
     */
    private boolean setRole(ISaikuConnection con, String roleName, SaikuDatasource datasource) {
        if (con.getConnection() instanceof OlapConnection) {
            OlapConnection c = (OlapConnection) con.getConnection();

            log.info("Setting role to datasource:" + datasource.getName() + " role:" + roleName);
            try {
                if (StringUtils.isNotBlank(roleName)
                        && SaikuMondrianHelper.isMondrianConnection(c)
                        && roleName.split(",").length > 1) {
                    SaikuMondrianHelper.setRoles(c, roleName.split(","));
                } else {
                    c.setRoleName(roleName);
                }
                return true;
            } catch (Exception e) {
                log.error("Error setting role: " + roleName, e);
                throw new SaikuAccessDeniedException(
                        "Access denied: role \"" + roleName + "\" could not be applied on datasource \""
                                + datasource.getName() + "\".",
                        e);
            }
        }
        return false;
    }

    private List<String> getSpringRoles() {
        List<String> roles = new ArrayList<>();
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Collection<? extends GrantedAuthority> auths =
                    SecurityContextHolder.getContext().getAuthentication().getAuthorities();
            for (GrantedAuthority a : auths) {
                roles.add(a.getAuthority());
            }
        }
        return roles;
    }

    private List<String> getConnectionRoles(ISaikuConnection con) {
        if (con.getDatasourceType().equals(ISaikuConnection.OLAP_DATASOURCE)
                && con.getConnection() instanceof OlapConnection) {
            OlapConnection c = (OlapConnection) con.getConnection();
            try {
                return c.getAvailableRoleNames();
            } catch (OlapException e) {
                log.error("Error getting connection roles", e);
            }
        }
        return new ArrayList<>();
    }

    private Map<String, List<String>> getRoleMapping(SaikuDatasource datasource) {
        Map<String, List<String>> result = new HashMap<>();
        if (datasource.getProperties().containsKey(ISaikuConnection.SECURITY_LOOKUP_KEY)) {
            String mappings = datasource.getProperties().getProperty(ISaikuConnection.SECURITY_LOOKUP_KEY);
            if (mappings != null) {
                String[] maps = mappings.split(";");
                for (String map : maps) {
                    String[] m = map.split("=");
                    // saiku#1972: a blank value (ROLE_X= ) maps to no role, not to a role named " ".
                    if (m.length == 2 && StringUtils.isNotBlank(m[1])) {
                        if (!result.containsKey(m[0])) {
                            result.put(m[0], new ArrayList<String>());
                        }
                        result.get(m[0]).add(m[1]);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Opens a brand-new connection for {@code datasource}. Protected as a test seam: the
     * connection cache's behaviour is asserted by injecting fakes here rather than by standing up a
     * real warehouse.
     */
    protected ISaikuConnection connect(String name, SaikuDatasource datasource) {
        try {
            ISaikuConnection con = SaikuConnectionFactory.getConnection(datasource);
            if (con.initialized()) {
                return con;
            }
        } catch (Exception e) {
            log.error("Error connecting: " + name, e);
        }

        return null;
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {

        stream.defaultReadObject();
        connections = new HashMap<>();
    }
}
