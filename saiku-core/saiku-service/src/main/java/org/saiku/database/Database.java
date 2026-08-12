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
import java.sql.PreparedStatement;
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
import org.springframework.security.crypto.password.PasswordEncoder;

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
            loadBank();
        } catch (Exception e) {
            log.warn("Bank (bridge demo) sample data not loaded: {}", e.getMessage());
        }
        try {
            loadEarthquakes();
        } catch (Exception e) {
            log.warn("Earthquakes sample data not loaded: {}", e.getMessage());
        }
        loadLegacyDatasources();
        // saiku#1223: rebuild the workspace datasource cache from disk after the demo
        // loaders. addDatasource() caches under the RAW name ("bank") while the on-disk
        // .sds scan keys the same datasource workspace-prefixed ("unknown_bank"), so a
        // first-time registration otherwise shows BOTH in /discover for the rest of the
        // session. load() swaps in a fresh disk-scanned map (loadDatasources replaces,
        // not merges), leaving only the canonical prefixed names. Idempotent — the same
        // call every refreshAllConnections() makes.
        try {
            dsm.load();
        } catch (Exception e) {
            log.warn("Post-seed datasource cache reload failed: {}", e.getMessage());
        }
    }

    private static String expandSaikuHome(String s) {
        if (s == null) return null;
        String home = System.getProperty("saiku.home");
        if (home == null || home.isEmpty()) return s;
        return s.replace("../../", home + "/").replace("${saiku.home}", home);
    }

    /**
     * saiku#1223: true when any registered datasource carries the given well-known {@code id}
     * property. Used by the demo loaders (foodmart, bank, earthquakes) to skip re-registration
     * when the repository already holds the datasource — the tenant prefix (e.g. {@code
     * unknown_}) is applied after {@code addDatasource()}, so a name lookup misses the existing
     * copy and the loader would register a raw-named duplicate ("bank" alongside
     * "unknown_bank"). Fail-open: if the manager isn't ready, report not-registered so the
     * legacy registration path still runs (worst case: the pre-fix duplicate).
     */
    private boolean isDatasourceRegistered(IDatasourceManager mgr, String id) {
        try {
            return containsDatasourceId(mgr.getDatasources(null).values(), id);
        } catch (Exception notReady) {
            return false;
        }
    }

    /** Pure scan over datasource properties for a matching {@code id} — package-visible for tests. */
    static boolean containsDatasourceId(java.util.Collection<SaikuDatasource> datasources, String id) {
        if (datasources == null || id == null) {
            return false;
        }
        for (SaikuDatasource ds : datasources) {
            if (ds != null
                    && ds.getProperties() != null
                    && id.equals(ds.getProperties().getProperty("id"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * saiku#1692: build the Mondrian location for an embedded-H2 demo datasource with
     * forward-slash paths. On Windows the data dir arrives as a native backslash path
     * ({@code ${saiku.home}} is {@code Path.toString()}), and backslashes inside the
     * {@code Jdbc=} URL break the Mondrian-Calcite backend: it embeds the H2 URL in its
     * model JSON, where {@code \U} in {@code C:\Users} is an invalid string escape —
     * every connect then dies with {@code JsonParseException: Unrecognized character
     * escape 'U'} (boot-time GraphQL cube-generator init included). H2 accepts forward
     * slashes on all platforms, and {@code File.toURI()} catalog URIs are already clean.
     * Package-visible for unit tests; byte-identical to the legacy concatenation when
     * the dir has no backslashes.
     */
    static String h2MondrianLocation(String dataDir, String dbName, String mode, String catalogUri) {
        String dir = dataDir == null ? "" : dataDir.replace('\\', '/');
        StringBuilder sb = new StringBuilder("jdbc:mondrian:Jdbc=jdbc:h2:")
                .append(dir)
                .append('/')
                .append(dbName);
        if (mode != null && !mode.isEmpty()) {
            sb.append(";MODE=").append(mode);
        }
        sb.append(";Catalog=").append(catalogUri).append(";JdbcDrivers=org.h2.Driver");
        return sb.toString();
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
                boolean alreadySeeded = isDatasourceRegistered(dsm, "4432dd20-fcae-11e3-a3ac-0800200c9a66");
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
                    p.setProperty("location", h2MondrianLocation(dsm.getFoodmartdir(), "foodmart", null, catalogUri));
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

    /**
     * Bridge (many-to-many) demo: the joint-accounts "Bank" schema. Its mm_*
     * tables live in the SAME H2 database as FoodMart (distinct names, no
     * clash), so this reuses FoodMart's url + data dir — no extra config. On
     * first run it RUNSCRIPTs bank.sql and registers the Bank datasource
     * (two cubes: full-count + weighted). Requires the Calcite backend.
     */
    private void loadBank() throws SQLException {
        String url = servletContext.getInitParameter("foodmart.url");
        if (url == null || url.equals("${foodmart_url}")) {
            return;
        }
        JdbcDataSource ds2 = new JdbcDataSource();
        ds2.setURL(dsm.getFoodmarturl());
        ds2.setUser("sa");
        ds2.setPassword("");

        Connection c = ds2.getConnection();
        DatabaseMetaData dbm = c.getMetaData();
        // Guard on the latest table in bank.sql (mm_monthly, the monthly
        // fixture for the Monthly Revenue cube + TimeCalc demos —
        // mondrian-saiku#112 / saiku#1220). Upgrading from a pre-#1220
        // demo will find this table missing and re-run the (idempotent
        // DROP IF EXISTS / CREATE TABLE) bank.sql to add it. Older guards
        // on mm_owner / mm_txn would short-circuit and leave Mondrian
        // crashing on "Table 'mm_calendar' does not exist".
        ResultSet tables = dbm.getTables(null, null, "mm_monthly", null);
        if (tables.next()) {
            // Already loaded.
            return;
        }
        Statement statement = c.createStatement();
        statement.execute("RUNSCRIPT FROM '" + dsm.getFoodmartdir() + "/bank.sql'");
        statement.executeQuery("select 1");

        String schema = null;
        try {
            schema = readFile(dsm.getFoodmartdir() + "/Bank.xml", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Can't read Bank schema file", e);
        }
        try {
            dsm.addSchema(schema, "/datasources/bank.xml", null);
        } catch (Exception e) {
            log.error("Can't add Bank schema file to repo", e);
        }
        // saiku#1223: after PR #1220 the mm_monthly guard above deliberately re-runs this
        // block on pre-#1220 homes to add the new table — but the datasource registration
        // below must NOT re-run when the repository already carries Bank (as unknown_bank),
        // or /discover lists it twice. Same well-known-UUID scan the foodmart loader uses.
        if (isDatasourceRegistered(dsm, "4432dd20-fcae-11e3-a3ac-0800200c9a68")) {
            log.info("Bank datasource already present in repository; skipping re-registration.");
            return;
        }
        String catalogUri =
                new java.io.File(dsm.getFoodmartdir() + "/Bank.xml").toURI().toString();
        Properties p = new Properties();
        p.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
        p.setProperty("location", h2MondrianLocation(dsm.getFoodmartdir(), "foodmart", null, catalogUri));
        p.setProperty("username", "sa");
        p.setProperty("password", "");
        p.setProperty("id", "4432dd20-fcae-11e3-a3ac-0800200c9a68");
        SaikuDatasource ds = new SaikuDatasource("bank", SaikuDatasource.Type.OLAP, p);
        try {
            dsm.addDatasource(ds);
        } catch (Exception e) {
            log.error("Can't add Bank data source to repo", e);
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
                // saiku#1223: same duplicate-registration class as bank — skip when the
                // repository already carries Earthquakes under its well-known UUID.
                if (isDatasourceRegistered(dsm, "4432dd20-fcae-11e3-a3ac-0800200c9a67")) {
                    log.info("Earthquakes datasource already present in repository; skipping re-registration.");
                    return;
                }
                Properties p = new Properties();
                p.setProperty("advanced", "true");

                p.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
                p.setProperty(
                        "location",
                        h2MondrianLocation(
                                dsm.getEarthquakeDir(),
                                "earthquakes",
                                "MySQL",
                                new java.io.File(dsm.getEarthquakeSchema())
                                        .toURI()
                                        .toString()));
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

        try (Statement statement = c.createStatement()) {
            statement.execute("ALTER TABLE users ALTER COLUMN password VARCHAR(100) DEFAULT NULL");
        }

        encryptUserPasswords(c, passwordEncoder);

        try (Statement logStatement = c.createStatement()) {
            logStatement.execute("INSERT INTO LOG(log) VALUES('update passwords');");
        }
    }

    /**
     * Bcrypt every user's stored password, binding the username + hash as
     * PreparedStatement parameters.
     *
     * <p>saiku#1155: the previous implementation string-concatenated the
     * DB-sourced {@code username} into the UPDATE, so a username containing a
     * single quote broke the migration and a crafted value such as
     * {@code x' OR '1'='1} rewrote EVERY row's password with one hash
     * (CWE-89). Parameter binding closes that. Package-private + static so the
     * security regression test exercises this exact code path.
     */
    static void encryptUserPasswords(Connection c, PasswordEncoder encoder) throws SQLException {
        try (Statement read = c.createStatement();
                ResultSet result = read.executeQuery("select username, password from users");
                PreparedStatement update = c.prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
            while (result.next()) {
                String hashedPassword = encoder.encode(result.getString("password"));
                update.setString(1, hashedPassword);
                update.setString(2, result.getString("username"));
                update.executeUpdate();
            }
        }
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
