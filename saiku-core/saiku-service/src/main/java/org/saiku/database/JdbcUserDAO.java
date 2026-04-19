package org.saiku.database;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import javax.servlet.ServletContext;
import org.apache.commons.lang3.ArrayUtils;
import org.saiku.UserDAO;
import org.saiku.database.dto.SaikuUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class JdbcUserDAO extends JdbcDaoSupport implements UserDAO {

    private final Properties prop = new Properties();
    private final ClassLoader loader = Thread.currentThread().getContextClassLoader();
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private ServletContext servletContext;

    public JdbcUserDAO() {
        InputStream stream = loader.getResourceAsStream("database-queries.properties");
        try {
            prop.load(stream);
        } catch (IOException e) {
            e.printStackTrace();
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

    private static final class UserMapper implements RowMapper {
        public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
            SaikuUser user = new SaikuUser();
            user.setId(rs.getInt("user_id"));
            user.setUsername(rs.getString("username"));
            user.setEmail(rs.getString("email"));
            user.setPassword(rs.getString("password"));
            if (rs.getString("ROLES") != null) {
                List<String> list =
                        new ArrayList(Arrays.asList(rs.getString("ROLES").split(",")));
                String[] stockArr = new String[list.size()];
                stockArr = list.toArray(stockArr);
                user.setRoles(stockArr);
            }
            return user;
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
