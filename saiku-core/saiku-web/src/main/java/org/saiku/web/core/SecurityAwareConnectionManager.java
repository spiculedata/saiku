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
import org.saiku.service.util.security.MondrianRolePolicy;
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
                return name + "-" + username;
            }
            String principal = currentPrincipalName();
            if (principal != null) {
                return name + "-" + principal;
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

        // saiku#779: resolution lives in MondrianRolePolicy so the admin role preview
        // (/saiku/admin/roles/preview) runs exactly the code enforced here.
        MondrianRolePolicy.Mode mode = MondrianRolePolicy.modeOf(datasource);
        if (mode == MondrianRolePolicy.Mode.ONE2ONE || mode == MondrianRolePolicy.Mode.LOOKUP) {
            List<String> resolved = MondrianRolePolicy.resolveMondrianRoles(
                    mode,
                    getSpringRoles(),
                    mode == MondrianRolePolicy.Mode.ONE2ONE ? getConnectionRoles(con) : null,
                    MondrianRolePolicy.mappingOf(datasource));
            String roleName = resolved.isEmpty() ? null : String.join(",", resolved);

            if (roleName == null) {
                // saiku#1968 (CWE-863): no Spring authority intersected the cube's roles (one2one)
                // or mapped to a Mondrian role (lookup). Deny a non-admin instead of falling
                // through to setRoleName(null) = Mondrian root.
                enforceRoleResolvedOrAdmin(datasource);
            }

            if (setRole(con, roleName, datasource)) {
                return con;
            }
            if (roleName != null && con.getConnection() instanceof OlapConnection) {
                // saiku#779: the role resolved but couldn't be applied — Mondrian rejects a role
                // its schema doesn't declare (a mapping typo, or a role renamed in the schema).
                // The connection still carries no role, i.e. Mondrian root, so deny rather than
                // hand out full access. Applies to admins too: this is a misconfiguration.
                String ds = datasource == null ? "?" : datasource.getName();
                log.warn(
                        "saiku#779: denying connection on datasource \"{}\" — resolved Mondrian role(s) "
                                + "\"{}\" could not be applied (not declared in the schema?) (fail-closed).",
                        ds,
                        roleName);
                throw new SaikuAccessDeniedException(
                        "Access denied: your role on datasource \"" + ds + "\" is misconfigured.");
            }
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

    private ISaikuConnection connect(String name, SaikuDatasource datasource) {
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
