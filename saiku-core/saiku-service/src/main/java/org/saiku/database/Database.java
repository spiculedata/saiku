package org.saiku.database;

import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.FileUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.IDatasourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Created by bugg on 01/05/14.
 */
public class Database {

    @Autowired
    ServletContext servletContext;

    private JdbcDataSource ds;
    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private IDatasourceManager dsm;

    public Database() {}

    public void setDatasourceManager(IDatasourceManager dsm) {
        this.dsm = dsm;
    }

    public ServletContext getServletContext() {
        return servletContext;
    }

    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    public void init() throws SQLException {
        initDB();
        loadUsers();
        try {
            loadFoodmart();
        } catch (Exception e) {
            log.warn("Foodmart sample data not loaded: {}", e.getMessage());
        }
        try {
            loadEarthquakes();
        } catch (Exception e) {
            log.warn("Earthquakes sample data not loaded: {}", e.getMessage());
        }
        loadLegacyDatasources();
    }

    private static String expandSaikuHome(String s) {
        if (s == null) return null;
        String home = System.getProperty("saiku.home");
        if (home == null || home.isEmpty()) return s;
        return s.replace("../../", home + "/").replace("${saiku.home}", home);
    }

    private void initDB() {
        String url = expandSaikuHome(servletContext.getInitParameter("db.url"));
        String user = servletContext.getInitParameter("db.user");
        String pword = servletContext.getInitParameter("db.password");
        ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(pword);
    }

    private void loadFoodmart() throws SQLException {
        String url = servletContext.getInitParameter("foodmart.url");
        String user = servletContext.getInitParameter("foodmart.user");
        String pword = servletContext.getInitParameter("foodmart.password");
        if (url != null && !url.equals("${foodmart_url}")) {
            JdbcDataSource ds2 = new JdbcDataSource();
            ds2.setURL(dsm.getFoodmarturl());
            ds2.setUser(user);
            ds2.setPassword(pword);

            Connection c = ds2.getConnection();
            DatabaseMetaData dbm = c.getMetaData();
            ResultSet tables = dbm.getTables(null, null, "account", null);

            if (!tables.next()) {
                // First-time setup: the H2 file exists but the FoodMart tables
                // haven't been loaded yet. Run the seed SQL regardless so the
                // database actually has data.
                Statement statement = c.createStatement();

                statement.execute("RUNSCRIPT FROM '" + dsm.getFoodmartdir() + "/foodmart_h2.sql'");

                statement.execute("alter table \"time_by_day\" add column \"date_string\" varchar(30);"
                        + "update \"time_by_day\" "
                        + "set \"date_string\" = TO_CHAR(\"the_date\", 'yyyy/mm/dd');");

                // If saiku-launcher's stageDefaultDatasource already seeded a
                // foodmart datasource into the repository, skip the schema +
                // datasource registration below to avoid a duplicate entry in
                // discover. The repository tenant prefix (e.g. "unknown_") is
                // applied after addDatasource(), so a getDatasource("foodmart")
                // name check misses the staged copy. Instead, scan all
                // registered datasources for the well-known foodmart UUID —
                // robust against any tenant prefix.
                boolean alreadySeeded = false;
                try {
                    for (SaikuDatasource ds : dsm.getDatasources(null).values()) {
                        if (ds != null
                                && ds.getProperties() != null
                                && "4432dd20-fcae-11e3-a3ac-0800200c9a66"
                                        .equals(ds.getProperties().getProperty("id"))) {
                            alreadySeeded = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // dsm not ready / iteration failed — fall through to the
                    // legacy registration path below (worst case: duplicate).
                }
                if (!alreadySeeded) {
                    String schema = null;
                    try {
                        schema = readFile(dsm.getFoodmartschema(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        log.error("Can't read schema file", e);
                    }
                    try {
                        dsm.addSchema(schema, "/datasources/foodmart4.xml", null);
                    } catch (Exception e) {
                        log.error("Can't add schema file to repo", e);
                    }
                    String catalogUri =
                            new java.io.File(dsm.getFoodmartschema()).toURI().toString();
                    Properties p = new Properties();
                    p.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                    p.setProperty(
                            "location",
                            "jdbc:mondrian:Jdbc=jdbc:h2:" + dsm.getFoodmartdir() + "/foodmart;" + "Catalog="
                                    + catalogUri + ";JdbcDrivers=org.h2.Driver");
                    p.setProperty("username", "sa");
                    p.setProperty("password", "");
                    p.setProperty("id", "4432dd20-fcae-11e3-a3ac-0800200c9a66");
                    SaikuDatasource ds = new SaikuDatasource("foodmart", SaikuDatasource.Type.OLAP, p);

                    try {
                        dsm.addDatasource(ds);
                    } catch (Exception e) {
                        log.error("Can't add data source to repo", e);
                    }
                } else {
                    log.info("Foodmart datasource already present in repository "
                            + "(seeded by saiku-launcher); skipping built-in registration.");
                }

            } else {
                Statement statement = c.createStatement();

                statement.executeQuery("select 1");
            }
        }
    }

    private void loadEarthquakes() throws SQLException {
        String url = servletContext.getInitParameter("earthquakes.url");
        String user = servletContext.getInitParameter("earthquakes.user");
        String pword = servletContext.getInitParameter("earthquakes.password");

        if (url != null && !url.equals("${earthquake_url}")) {
            JdbcDataSource ds3 = new JdbcDataSource();
            ds3.setURL(dsm.getEarthquakeUrl());
            ds3.setUser(user);
            ds3.setPassword(pword);

            Connection c = ds3.getConnection();
            DatabaseMetaData dbm = c.getMetaData();
            ResultSet tables = dbm.getTables(null, null, "earthquakes", null);
            String schema = null;

            if (!tables.next()) {
                Statement statement = c.createStatement();

                statement.execute("RUNSCRIPT FROM '" + dsm.getEarthquakeDir() + "/earthquakes.sql'");
                statement.executeQuery("select 1");

                try {
                    schema = readFile(dsm.getEarthquakeSchema(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.error("Can't read schema file", e);
                }
                try {
                    dsm.addSchema(schema, "/datasources/earthquakes.xml", null);
                } catch (Exception e) {
                    log.error("Can't add schema file to repo", e);
                }
                Properties p = new Properties();
                p.setProperty("advanced", "true");

                p.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                p.setProperty(
                        "location",
                        "jdbc:mondrian:Jdbc=jdbc:h2:" + dsm.getEarthquakeDir() + "/earthquakes;MODE=MySQL;"
                                + "Catalog=" + new java.io.File(dsm.getEarthquakeSchema()).toURI()
                                + ";JdbcDrivers=org.h2.Driver");
                p.setProperty("username", "sa");
                p.setProperty("password", "");
                p.setProperty("id", "4432dd20-fcae-11e3-a3ac-0800200c9a67");
                SaikuDatasource ds = new SaikuDatasource("earthquakes", SaikuDatasource.Type.OLAP, p);

                try {
                    dsm.addDatasource(ds);
                } catch (Exception e) {
                    log.error("Can't add data source to repo", e);
                }

                try {
                    dsm.saveInternalFile("/homes/home:admin/sample_reports", null, null);
                    String exts[] = {"saiku"};
                    Iterator<File> files = FileUtils.iterateFiles(new File("../../data/sample_reports"), exts, false);

                    while (files.hasNext()) {
                        File f = files.next();
                        dsm.saveInternalFile(
                                "/homes/home:admin/sample_reports/" + f.getName(),
                                FileUtils.readFileToString(f.getAbsoluteFile()),
                                null);
                        files.remove();
                    }

                } catch (IOException e) {
                    log.warn("Failed to seed sample report file", e);
                }

            } else {
                Statement statement = c.createStatement();

                statement.executeQuery("select 1");
            }
        }
    }

    private static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    private void loadUsers() throws SQLException {

        Connection c = ds.getConnection();

        Statement statement = c.createStatement();
        statement.execute("CREATE TABLE IF NOT EXISTS LOG(time TIMESTAMP AS CURRENT_TIMESTAMP NOT NULL, log CLOB);");

        statement.execute("CREATE TABLE IF NOT EXISTS USERS(user_id INT(11) NOT NULL AUTO_INCREMENT, "
                + "username VARCHAR(45) NOT NULL UNIQUE, password VARCHAR(100) NOT NULL, email VARCHAR(100), "
                + "enabled TINYINT NOT NULL DEFAULT 1, PRIMARY KEY(user_id));");

        statement.execute("CREATE TABLE IF NOT EXISTS USER_ROLES (\n"
                + "  user_role_id INT(11) NOT NULL AUTO_INCREMENT,username VARCHAR(45),\n"
                + "  user_id INT(11) NOT NULL REFERENCES USERS(user_id),\n"
                + "  ROLE VARCHAR(45) NOT NULL,\n"
                + "  PRIMARY KEY (user_role_id));");

        ResultSet result = statement.executeQuery("select count(*) as c from LOG where log = 'insert users'");
        result.next();
        if (result.getInt("c") == 0) {
            dsm.createUser("admin");
            dsm.createUser("smith");
            statement.execute("INSERT INTO users(username,password,email, enabled)\n"
                    + "VALUES ('admin','admin', 'test@admin.com',TRUE);"
                    + "INSERT INTO users(username,password,enabled)\n"
                    + "VALUES ('smith','smith', TRUE);");
            statement.execute("INSERT INTO user_roles (user_id, username, ROLE)\n"
                    + "VALUES (1, 'admin', 'ROLE_USER');" + "INSERT INTO user_roles (user_id, username, ROLE)\n"
                    + "VALUES (1, 'admin', 'ROLE_ADMIN');" + "INSERT INTO user_roles (user_id, username, ROLE)\n"
                    + "VALUES (2, 'smith', 'ROLE_USER');");

            statement.execute("INSERT INTO LOG(log) VALUES('insert users');");
        }

        String encrypt = servletContext.getInitParameter("db.encryptpassword");
        if (encrypt.equals("true") && !checkUpdatedEncyption()) {
            log.debug("Encrypting User Passwords");
            updateForEncyption();
            log.debug("Finished Encrypting Passwords");
        }
    }

    private boolean checkUpdatedEncyption() throws SQLException {
        Connection c = ds.getConnection();

        Statement statement = c.createStatement();
        ResultSet result = statement.executeQuery("select count(*) as c from LOG where log = 'update passwords'");
        result.next();
        return result.getInt("c") != 0;
    }

    private void updateForEncyption() throws SQLException {
        Connection c = ds.getConnection();

        Statement statement = c.createStatement();
        statement.execute("ALTER TABLE users ALTER COLUMN password VARCHAR(100) DEFAULT NULL");

        ResultSet result = statement.executeQuery("select username, password from users");

        while (result.next()) {
            statement = c.createStatement();

            String pword = result.getString("password");
            String hashedPassword = passwordEncoder.encode(pword);
            String sql = "UPDATE users " + "SET password = '" + hashedPassword + "' WHERE username = '"
                    + result.getString("username") + "'";
            statement.executeUpdate(sql);
        }
        statement = c.createStatement();

        statement.execute("INSERT INTO LOG(log) VALUES('update passwords');");
    }

    private void loadLegacyDatasources() throws SQLException {
        Connection c = ds.getConnection();

        Statement statement = c.createStatement();
        ResultSet result = statement.executeQuery("select count(*) as c from LOG where log = 'insert datasources'");

        result.next();
        if (result.getInt("c") == 0) {
            statement.execute("INSERT INTO LOG(log) VALUES('insert datasources');");
        }
    }

    public List<String> getUsers() throws java.sql.SQLException {
        // Stub for EE.
        return null;
    }

    public void addUsers(List<String> l) throws java.sql.SQLException {
        // Stub for EE.
    }
}
