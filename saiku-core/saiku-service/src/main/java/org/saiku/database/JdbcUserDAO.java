/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import jakarta.servlet.ServletContext;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.ArrayUtils;
import org.saiku.UserDAO;
import org.saiku.database.dto.SaikuUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class JdbcUserDAO extends JdbcDaoSupport implements UserDAO {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserDAO.class);

    private final Properties prop = new Properties();
    private final ClassLoader loader = Thread.currentThread().getContextClassLoader();
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private ServletContext servletContext;

    public JdbcUserDAO() {
        InputStream stream = loader.getResourceAsStream("database-queries.properties");
        if (stream == null) {
            stream = loader.getResourceAsStream("/database-queries.properties");
        }
        if (stream != null) {
            try (InputStream in = stream) {
                prop.load(in);
            } catch (IOException e) {
                log.error("Failed to load database-queries.properties", e);
            }
        }
    }

    public SaikuUser insert(SaikuUser user) {
        String sql = prop.getProperty("insertUser");
        String encrypt = servletContext.getInitParameter("db.encryptpassword");

        if (encrypt.equals("true")) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        String newsql = prop.getProperty("maxUser");
        getJdbcTemplate().update(sql, user.getUsername(), user.getPassword(), user.getEmail(), Boolean.valueOf(true));

        Integer name = getJdbcTemplate().queryForObject(newsql, new Object[] {user.getUsername()}, Integer.class);

        String updatesql = prop.getProperty("updateRole");

        String[] roles = user.getRoles();
        String[] roles2 = {"ROLE_USER"};
        String[] both = ArrayUtils.addAll(roles2, roles);

        user.setRoles(both);
        getJdbcTemplate().update(updatesql, name, Integer.valueOf(user.getId()));

        user.setId(name);

        insertRole(user);
        // saiku#1438: name/displayName ride their own statement so the legacy INSERT (which
        // predates the columns) stays untouched for every non-SCIM caller.
        updateUserProfile(user);
        return user;
    }

    public void insertRole(SaikuUser user) {
        String sql = prop.getProperty("insertRole");
        String removeSQL = prop.getProperty("deleteRole");

        getJdbcTemplate().update(removeSQL, user.getId());

        if (user.getRoles() != null) {
            for (String r : user.getRoles()) {
                if (r != null && !r.equals("")) {
                    getJdbcTemplate().update(sql, Integer.valueOf(user.getId()), user.getUsername(), r);
                }
            }
        }
    }

    public void deleteUser(SaikuUser user) {
        String sql = prop.getProperty("deleteRoleByUserName");
        String sql2 = prop.getProperty("deleteUserByUserName");
        getJdbcTemplate().update(sql, user.getUsername());
        getJdbcTemplate().update(sql2, user.getUsername());
    }

    public void deleteRole(SaikuUser user) {
        String role = "";
        String sql = prop.getProperty("deleteRoleByRoleAndUser");
        getJdbcTemplate().update(sql, Integer.valueOf(user.getId()), role);
    }

    public String[] getRoles(SaikuUser user) {
        String sql = prop.getProperty("getRole");
        String roles = getJdbcTemplate().queryForObject(sql, new Object[] {user.getId()}, String.class);
        if (roles != null) {
            List<String> list = new ArrayList(Arrays.asList(roles.split(",")));
            String[] stockArr = new String[list.size()];
            return list.toArray(stockArr);
        }
        return null;
    }

    public SaikuUser findByUserId(int userId) {

        return (SaikuUser) getJdbcTemplate()
                .query(prop.getProperty("getUserById"), new Object[] {userId}, new UserMapper())
                .get(0);
    }

    public Collection findAllUsers() {
        return getJdbcTemplate().query(prop.getProperty("getAllUsers"), new UserMapper());
    }

    public void deleteUser(String username) {
        String sql = prop.getProperty("deleteRoleByUserId");
        String newsql = prop.getProperty("deleteUserById");
        getJdbcTemplate().update(sql, username);
        getJdbcTemplate().update(newsql, username);
    }

    public SaikuUser updateUser(SaikuUser user, boolean updatepassword) {
        String sql;
        if (updatepassword) {
            sql = prop.getProperty("updateUserWithPassword");
        } else {
            sql = prop.getProperty("updateUser");
        }

        String newsql = prop.getProperty("maxUser");
        String encrypt = servletContext.getInitParameter("db.encryptpassword");

        if (updatepassword) {
            if (encrypt.equals("true")) {
                user.setPassword(passwordEncoder.encode(user.getPassword()));
            }
            getJdbcTemplate()
                    .update(
                            sql,
                            user.getUsername(),
                            user.getPassword(),
                            user.getEmail(),
                            Boolean.valueOf(true),
                            user.getId());
        } else {
            getJdbcTemplate().update(sql, user.getUsername(), user.getEmail(), Boolean.valueOf(true), user.getId());
        }

        Integer name = getJdbcTemplate().queryForObject(newsql, new Object[] {user.getUsername()}, Integer.class);

        String updatesql = prop.getProperty("updateRole");

        getJdbcTemplate().update(updatesql, name, Integer.valueOf(user.getId()));

        user.setId(name);

        insertRole(user);
        return user;
    }

    public void updateRoles(SaikuUser user) {
        insertRole(user);
    }

    /**
     * saiku#1438 (SCIM {@code active=false}) — flip the {@code USERS.ENABLED} flag on its own.
     *
     * <p>{@link #updateUser} and {@link #updateUser(SaikuUser, boolean)} both hard-code
     * {@code enabled = TRUE} in their SQL, so there was previously no way to deactivate an
     * account short of a hand-edited row. A dedicated statement is required: reusing the full-row
     * UPDATE to carry a flag would re-write username/email from whatever DTO the caller held.
     */
    public void updateUserEnabled(int userId, boolean enabled) {
        String sql = prop.getProperty("updateUserEnabled");
        if (sql == null) {
            log.warn("updateUserEnabled query is not configured — cannot change ENABLED for user_id={}", userId);
            return;
        }
        getJdbcTemplate().update(sql, Boolean.valueOf(enabled), Integer.valueOf(userId));
    }

    /**
     * saiku#1438 — write the SCIM display attributes (name.givenName, name.familyName,
     * displayName). Separate from the row update for the same reason as
     * {@link #updateUserEnabled}: the legacy UPDATE statements predate the columns and hard-code
     * the rest of the row, so a profile sync must not drag username/email/ENABLED along with it.
     * A NULL means "clear it" — which is exactly what a SCIM attribute removal asks for.
     */
    public void updateUserProfile(SaikuUser user) {
        String sql = prop.getProperty("updateUserProfile");
        if (sql == null) {
            return;
        }
        try {
            getJdbcTemplate()
                    .update(
                            sql,
                            user.getGivenName(),
                            user.getFamilyName(),
                            user.getDisplayName(),
                            Integer.valueOf(user.getId()));
        } catch (Exception e) {
            // A pre-migration H2 file without the GIVEN_NAME/... columns must not break user
            // creation — display attributes are cosmetic, unlike the enabled flag.
            log.warn("Could not persist display attributes for user_id={} (columns missing?)", user.getId(), e);
        }
    }

    /**
     * saiku#1438 — write just the email address. Split from the display-attribute statement so a
     * query file that predates the SCIM columns still updates email, and vice versa: a missing
     * column degrades one attribute, never the whole profile sync.
     */
    public void updateUserEmail(int userId, String email) {
        String sql = prop.getProperty("updateUserEmail");
        if (sql == null) {
            return;
        }
        getJdbcTemplate().update(sql, email, Integer.valueOf(userId));
    }

    // Package-private (was private) so JdbcUserDAOUserMapperTest can drive mapRow directly against
    // controlled H2 ResultSets — covers the saiku#1809 PR4 USERS.ENABLED backward-compat branches
    // (disabled / SQL-NULL / missing-column fallback) without a mocking framework (Mockito is not on
    // this module's classpath).
    static final class UserMapper implements RowMapper {
        public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
            SaikuUser user = new SaikuUser();
            user.setId(rs.getInt("user_id"));
            user.setUsername(rs.getString("username"));
            user.setEmail(rs.getString("email"));
            user.setPassword(rs.getString("password"));
            // saiku#1809 PR4: surface the USERS.ENABLED column (already SELECTed by
            // getAllUsers/getUserById) so a disabled account is visible to callers — the scheduler's
            // owner-identity gate refuses to run a disabled owner's job. A missing column (older query)
            // or SQL NULL is treated as enabled, preserving legacy behaviour.
            boolean enabled = true;
            try {
                int e = rs.getInt("enabled");
                if (!rs.wasNull()) {
                    enabled = e != 0;
                }
            } catch (SQLException noSuchColumn) {
                // Query didn't select ENABLED — leave the default (enabled).
            }
            user.setEnabled(enabled);
            // saiku#1438: SCIM display attributes. Read defensively — a query that doesn't
            // select them, or a pre-migration table without the columns, degrades to "no name"
            // rather than failing the whole directory read.
            user.setGivenName(optionalString(rs, "GIVEN_NAME"));
            user.setFamilyName(optionalString(rs, "FAMILY_NAME"));
            user.setDisplayName(optionalString(rs, "DISPLAY_NAME"));
            if (rs.getString("ROLES") != null) {
                List<String> list =
                        new ArrayList(Arrays.asList(rs.getString("ROLES").split(",")));
                String[] stockArr = new String[list.size()];
                stockArr = list.toArray(stockArr);
                user.setRoles(stockArr);
            }
            return user;
        }

        /** Null-safe column read that tolerates the column not being selected / not existing. */
        private static String optionalString(ResultSet rs, String column) {
            try {
                return rs.getString(column);
            } catch (SQLException missing) {
                return null;
            }
        }
    }

    public ServletContext getServletContext() {
        return servletContext;
    }

    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }
}
