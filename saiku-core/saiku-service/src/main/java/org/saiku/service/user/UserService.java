/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.user;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.saiku.database.JdbcUserDAO;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Created by bugg on 01/05/14.
 */
public class UserService implements IUserManager, Serializable {

    private JdbcUserDAO uDAO;

    private IDatasourceManager iDatasourceManager;
    private DatasourceService datasourceService;
    private ISessionService sessionService;
    private List<String> adminRoles;
    private PasswordPolicy passwordPolicy = new PasswordPolicy();
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    public void setPasswordPolicy(PasswordPolicy passwordPolicy) {
        if (passwordPolicy != null) this.passwordPolicy = passwordPolicy;
    }

    public PasswordPolicy getPasswordPolicy() {
        return passwordPolicy;
    }

    public void setAdminRoles(List<String> adminRoles) {
        this.adminRoles = adminRoles;
    }

    public void setJdbcUserDAO(JdbcUserDAO jdbcUserDAO) {
        this.uDAO = jdbcUserDAO;
    }

    public void setiDatasourceManager(IDatasourceManager repo) {
        this.iDatasourceManager = repo;
    }

    public void setSessionService(ISessionService sessionService) {
        this.sessionService = sessionService;
    }

    public DatasourceService getDatasourceService() {
        return datasourceService;
    }

    public void setDatasourceService(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    public SaikuUser addUser(SaikuUser u) {
        // New user: enforce password policy before touching the DAO. The
        // policy only runs on raw passwords at point-of-entry — existing
        // hashed entries in the user table are never re-validated.
        passwordPolicy.validate(u.getUsername(), u.getPassword());
        uDAO.insert(u);
        uDAO.insertRole(u);
        iDatasourceManager.createUser(u.getUsername());
        return u;
    }

    public boolean deleteUser(SaikuUser u) {
        uDAO.deleteUser(u);
        iDatasourceManager.deleteFolder("homes/home:" + u.getUsername());
        return true;
    }

    public SaikuUser setUser(SaikuUser u) {
        return null;
    }

    public List<SaikuUser> getUsers() {
        Collection users = uDAO.findAllUsers();
        List<SaikuUser> l = new ArrayList<>();
        for (Object user : users) {
            l.add((SaikuUser) user);
        }
        return l;
    }

    public SaikuUser getUser(int id) {
        return uDAO.findByUserId(id);
    }

    /**
     * Whether {@code username} names an account that both EXISTS and is ENABLED (saiku#1809 PR4).
     * Reflects the authoritative {@code USERS.ENABLED} column via {@link #getUsers()}. Fail-closed: an
     * unknown/blank username, or any lookup error, returns {@code false} — never a best-effort grant.
     * The scheduler's owner-identity gate calls this so a disabled owner's job never runs.
     */
    public boolean isEnabled(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        try {
            for (SaikuUser u : getUsers()) {
                // saiku#1907 F4: case-insensitive identity — a job owned by an admin-typed
                // mixed-case name must still resolve its enabled-state (else the scheduler
                // fail-closes and auto-disables a legitimately-owned job).
                if (u != null && Usernames.sameUser(username, u.getUsername())) {
                    return u.isEnabled();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve enabled-state for user '{}' — treating as disabled (fail-closed)", username);
        }
        return false;
    }

    public String[] getRoles(SaikuUser user) {
        return uDAO.getRoles(user);
    }

    public void addRole(SaikuUser u) {
        uDAO.insertRole(u);
    }

    public void removeRole(SaikuUser u) {
        uDAO.deleteRole(u);
    }

    public void removeUser(String username) {
        SaikuUser u = getUser(Integer.parseInt(username));

        uDAO.deleteUser(username);

        iDatasourceManager.deleteFolder("homes/" + u.getUsername());
    }

    public SaikuUser updateUser(SaikuUser u, boolean updatepassword) {
        // Only validate when the caller is actually changing the password —
        // otherwise u.getPassword() may be null/empty/already-hashed.
        if (updatepassword) {
            passwordPolicy.validate(u.getUsername(), u.getPassword());
        }
        SaikuUser user = uDAO.updateUser(u, updatepassword);
        uDAO.updateRoles(u);

        return user;
    }

    /**
     * saiku#1732 — read the current user's roles AUTHORITATIVELY from Spring's
     * {@link SecurityContextHolder}, the deterministic per-request source, NOT from the session map.
     *
     * <p>Why this is the right source (and safe): the session {@code "roles"} entry is only ever
     * written FROM {@code SecurityContextHolder} authorities — {@code SessionService.createSession}
     * (:149-154) and {@code runAs} (:279/:283) are the only writers, and both copy
     * {@code getAuthorities()} verbatim. There is no session-only role shape. But the session entry
     * is populated lazily/conditionally, so under stateless Basic auth it was frequently empty,
     * making {@code isAdmin()} non-deterministically 403 the admin console (and every role-scoped
     * read flaky) across JVM launches. Reading the holder directly is superset-or-equal and always
     * fresh, so this can only make the read MORE accurate — it can never surface a role the
     * authenticated principal does not hold.
     *
     * <p>{@code runAs} impersonation is preserved: it sets {@code SecurityContextHolder} authorities
     * (and the session) to the same delegated role list for the duration, so the holder read yields
     * exactly the impersonated roles.
     *
     * <p>Fail-closed (saiku#1516): a missing/unauthenticated/anonymous principal yields an EMPTY
     * list, never null and never a grant — the guard is kept explicit here so the never-grant-on-
     * absent-auth regression stays locked.
     */
    private List<String> getCurrentUserRolesList() {
        Authentication auth = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        // Absent auth, unauthenticated, or an anonymous token -> no roles (fail-closed). An
        // AnonymousAuthenticationToken carries only ROLE_ANONYMOUS, which is never an admin/schema
        // role, but we exclude it explicitly so an anonymous principal can never contribute a role.
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new ArrayList<String>();
        }
        List<String> roles = new ArrayList<String>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga != null && ga.getAuthority() != null) {
                roles.add(ga.getAuthority());
            }
        }
        return roles;
    }

    public String[] getCurrentUserRoles() {
        List<String> roles = getCurrentUserRolesList();
        String[] rolesArray = new String[roles.size()];
        return roles.toArray(rolesArray);
    }

    public boolean isAdmin() {
        List<String> roles = getCurrentUserRolesList();

        // Fail-closed defence in depth (saiku#1516): deny on a null collection. Post-saiku#1732
        // getCurrentUserRolesList() never returns null (empty list on absent/anonymous auth), so
        // an absent principal already denies here via the empty-set disjoint below — this guard is
        // kept explicit so the never-grant-on-null regression stays locked regardless.
        if (roles == null) {
            return false;
        }

        return !Collections.disjoint(roles, adminRoles);
    }

    public void checkFolders() {

        String username = (String) sessionService.getAllSessionObjects().get("username");

        boolean home = true;
        if (username != null) {
            home = datasourceService.hasHomeDirectory(username);
        }
        if (!home) {
            datasourceService.createUserHome(username);
        }
    }

    public List<String> getAdminRoles() {
        return adminRoles;
    }

    public String getActiveUsername() {
        try {
            return (String) sessionService.getSession().get("username");
        } catch (Exception e) {
            log.error("Could not fetch username");
        }
        return null;
    }

    @Override
    public String getSessionId() {
        try {
            return (String) sessionService.getSession().get("sessionid");
        } catch (Exception e) {
            log.error("Could not get sessionid: " + e.getMessage());
        }
        return null;
    }
}
