package org.saiku.service.user;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.saiku.database.JdbcUserDAO;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.ISessionService;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.IDatasourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @SuppressWarnings("unchecked")
    private List<String> getCurrentUserRolesList() {
        // Snapshot the session objects ONCE, then read only from the local.
        // SessionService.getAllSessionObjects() rebuilds a fresh defensive copy from the
        // live sessionHolder on every call, and returns an empty map as soon as the
        // principal's entry is gone (SessionService.java:236-249). Calling it separately
        // for the guard and for the read was a time-of-check/time-of-use race: the entry
        // could vanish in between — logout() (:176), runAs()'s finally (:290) and
        // clearSessions() (:305) all remove it, and sessionHolder is keyed by a principal
        // whose equals() is username-based, so a second client on the same account is
        // enough to trigger it. When that happened this method returned null, which made
        // isAdmin() grant admin and getCurrentUserRoles() throw NPE.
        Map<String, Object> sessionObjects = sessionService == null ? null : sessionService.getAllSessionObjects();
        if (sessionObjects != null) {
            Object roles = sessionObjects.get("roles");
            if (roles != null) {
                return (List<String>) roles;
            }
        }

        return new ArrayList<String>();
    }

    public String[] getCurrentUserRoles() {
        List<String> roles = getCurrentUserRolesList();
        String[] rolesArray = new String[roles.size()];
        return roles.toArray(rolesArray);
    }

    public boolean isAdmin() {
        List<String> roles = getCurrentUserRolesList();

        // Absent roles deny. This is an authorization check: the safe direction on
        // missing input is fail-closed, and the previous default did the opposite —
        // it granted admin on a null collection. Kept as defence in depth behind the
        // snapshot in getCurrentUserRolesList(); either layer alone stops the race
        // above from granting admin, and deny is the correct answer here regardless.
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
