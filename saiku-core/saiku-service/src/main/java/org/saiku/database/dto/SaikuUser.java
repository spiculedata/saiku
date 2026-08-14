package org.saiku.database.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Created by bugg on 01/05/14.
 */
public class SaikuUser {
    private String username;
    private String email;

    /**
     * saiku#1165 hardening: the stored password (a {bcrypt} hash, populated from
     * the DB by JdbcUserDAO) must never be serialised back to a client — the
     * admin user-management endpoints (GET /admin/users, /users/{id}) returned
     * it otherwise. WRITE_ONLY keeps it accepting an inbound value on
     * create/update while omitting it from every response.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String[] roles;
    private int id;

    /**
     * Account-enabled flag, mirrored from the {@code USERS.ENABLED} column (saiku#1809 PR4). Defaults
     * to {@code true} — every code path that creates/updates a user writes {@code enabled=true}, so an
     * account is only ever disabled by a direct edit of the row. Surfacing it lets the scheduler's
     * owner-identity gate refuse to run a disabled owner's job.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String[] getRoles() {
        return roles;
    }

    public void setRoles(String[] roles) {
        this.roles = roles;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
