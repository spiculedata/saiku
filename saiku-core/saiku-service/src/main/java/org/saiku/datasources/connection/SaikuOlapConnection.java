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
package org.saiku.datasources.connection;

import static org.saiku.datasources.connection.encrypt.CryptoUtil.decrypt;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import mondrian.rolap.RolapConnection;
import org.olap4j.OlapConnection;
import org.olap4j.OlapWrapper;
import org.saiku.service.datasource.JdbcUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaikuOlapConnection implements ISaikuConnection {

    private String name;
    private boolean initialized = false;
    private Properties properties;
    private OlapConnection olapConnection;
    private String username;
    private String password;
    private String passwordenc;

    private static final Logger log = LoggerFactory.getLogger(SaikuOlapConnection.class);

    private static final String MONDRIAN_DRIVER = "mondrian.olap4j.MondrianOlap4jDriver";

    /**
     * saiku#2003: sub-protocols whose connect string is a {@code key=value;key=value} property
     * list parsed by olap4j / Mondrian, so a trailing {@code ';'} terminates the last pair.
     * A plain vendor driver URL ({@code jdbc:postgresql://host:5432/dmt}) is not a property
     * list — {@code ';'} there is not a separator but part of the database name.
     */
    private static final Set<String> PROPERTY_LIST_SUB_PROTOCOLS =
            Set.of("mondrian", "mondrian4", "xmla", "byolap", "avalon", "olap4j");

    /**
     * A URL is treated as a property list when one of its {@code ';'}-separated tokens (or the
     * leading token) is a {@code key=} pair. Covers the non-{@code jdbc:} Mondrian property lists
     * Saiku still stores for CSV datasources, e.g. {@code mondrian://datasources/foodmart.json;Catalog=...}.
     */
    private static final Pattern PROPERTY_LIST_URL = Pattern.compile("(?i)(?:^|;)\\s*[A-Za-z][A-Za-z0-9_.\\-]*\\s*=");

    /**
     * saiku#2003: whether {@code url} is a {@code key=value;} property list that needs a trailing
     * {@code ';'} terminator. Appending one to a plain JDBC URL corrupts its last component — a
     * Postgres database called {@code dmt} arrives as {@code dmt;} and the driver answers
     * {@code FATAL: database "dmt;" does not exist} (the reporter's stack trace, from this class'
     * own {@code openConnection} call).
     */
    static boolean needsPropertyTerminator(String url, String driver) {
        // The Mondrian driver gets JdbcUser=/JdbcPassword= appended below, which needs the
        // terminator regardless of how its location looks.
        if (MONDRIAN_DRIVER.equals(driver)) {
            return true;
        }
        if (url == null || url.isEmpty()) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            int colon = trimmed.indexOf(':', "jdbc:".length());
            String subProtocol = colon < 0 ? trimmed.substring(5) : trimmed.substring(5, colon);
            if (PROPERTY_LIST_SUB_PROTOCOLS.contains(subProtocol.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return PROPERTY_LIST_URL.matcher(trimmed).find();
    }

    /** Close a connection we are about to reject, without masking the reason we are rejecting it. */
    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignore) {
            // nothing useful to do — we are throwing the real diagnostic
        }
    }

    public SaikuOlapConnection(String name, Properties props) {
        this.name = name;
        this.properties = props;
    }

    public SaikuOlapConnection(Properties props) {
        this.properties = props;
        this.name = props.getProperty(ISaikuConnection.NAME_KEY);
    }

    public boolean connect() throws Exception {
        return connect(properties);
    }

    private String decryptPassword(String password) {

        if (password != null) {
            return decrypt(password);
        }
        return null;
    }

    public boolean connect(Properties props) throws Exception {
        String safemode = null;
        try {
            safemode = System.getProperty("saiku.safemode");
        } catch (Exception e) {
            // Safemode doesn't exist, not a problem.
        }
        if (safemode != null && safemode.equals("true")) {
            log.debug("Not starting connection " + name + ", Saiku in safe mode");
            return false;
        } else {
            if ((props.containsKey("csv") && props.getProperty("csv").equals("true"))
                    || (props.containsKey("enabled")
                            && props.getProperty("enabled").equals("true"))
                    || (!props.containsKey("enabled"))) {
                this.username = props.getProperty(ISaikuConnection.USERNAME_KEY);
                this.password = props.getProperty(ISaikuConnection.PASSWORD_KEY);
                String driver = props.getProperty(ISaikuConnection.DRIVER_KEY);
                this.passwordenc = props.getProperty(ISaikuConnection.PASSWORD_ENCRYPT_KEY);
                this.properties = props;
                String url = props.getProperty(ISaikuConnection.URL_KEY);

                if (this.passwordenc != null && this.passwordenc.equals("true")) {
                    this.password = decryptPassword(password);
                }
                if (url.contains("Mondrian=4")) {
                    url = url.replace("Mondrian=4; ", "");
                    url = url.replace("jdbc:mondrian", "jdbc:mondrian4");
                    url = url.replace("DataSource=", "DataSource=osgi:service/jdbc/");
                }
                // saiku#2003: only property-list URLs get the ';' terminator — see needsPropertyTerminator.
                if (url.length() > 0 && url.charAt(url.length() - 1) != ';' && needsPropertyTerminator(url, driver)) {
                    url += ";";
                }
                if (MONDRIAN_DRIVER.equals(driver)) {
                    if (username != null && username.length() > 0) {
                        url += "JdbcUser=" + username + ";";
                    }
                    if (password != null && password.length() > 0) {
                        url += "JdbcPassword=" + password + ";";
                    }
                }

                // Tweak database URL to follow JCR standards when using Jackrabbit
                if (url.contains("mondrian://") && url.contains("model")) {
                    String[] urlTokens = url.split(";");

                    if (!urlTokens[0].contains("mondrian://")) {
                        String[] jdbcTokens = urlTokens[0].split(":");

                        if (jdbcTokens.length == 5) {
                            String[] modelTokens = jdbcTokens[4].split("/");
                            String modelName = modelTokens[modelTokens.length - 1];

                            if (!modelName.endsWith("-csv.json")) {
                                modelName = modelName.substring(0, modelName.length() - 4) + "-csv.json";
                            }

                            jdbcTokens[4] = "model=mondrian://datasources/" + modelName;

                            urlTokens[0] = String.join(":", jdbcTokens);
                            url = String.join(";", urlTokens);
                        }
                    }
                }

                // saiku#1902: this is THE chokepoint — every load path (admin add, .sds descriptor
                // read at boot / on /discover/refresh, datasource save) funnels through here, so the
                // policy is applied to the FINAL url, after JdbcUser=/JdbcPassword= were appended
                // above. Checking earlier would let a ';'-injected username re-point Jdbc= behind
                // the check. The driver class is only initialised once it is known to be a
                // java.sql.Driver, so a descriptor can't run an arbitrary static initialiser.
                JdbcUrlPolicy.validate(url);
                JdbcUrlPolicy.loadDriverClass(driver);
                Connection connection = JdbcUrlPolicy.openConnection(url, username, password);

                if (connection != null) {
                    // saiku#2003: a driver that hands back a bare java.sql.Connection has no
                    // Mondrian schema behind it, so there is no cube to load — the old blind cast
                    // turned that into a ClassCastException with no explanation. Say what is wrong
                    // and what to do about it.
                    if (!(connection instanceof OlapWrapper)) {
                        closeQuietly(connection);
                        throw new IllegalStateException("Datasource '" + name + "' connects with "
                                + (driver == null ? "no driver class" : driver)
                                + " as a plain JDBC connection, with no OLAP schema attached, so no cube can be loaded"
                                + " from it. Generate a Mondrian schema for this connection with the Schema Generator, or"
                                + " point it at an existing Mondrian catalog (a MONDRIAN connection, whose location"
                                + " carries Catalog=...;JdbcDrivers=...), then reload the cube.");
                    }
                    final OlapWrapper wrapper = (OlapWrapper) connection;
                    OlapConnection tmpolapConnection = wrapper.unwrap(OlapConnection.class);

                    if (tmpolapConnection == null) {
                        throw new Exception("Connection is null");
                    }

                    log.info("Catalogs:" + tmpolapConnection.getOlapCatalogs().size());
                    olapConnection = tmpolapConnection;
                    initialized = true;
                    return true;
                }
            } else if (props.containsKey("enabled")
                    && props.getProperty("enabled").equals("false")) {
                log.info("Datasource marked as disabled.");
                return false;
            }
            return false;
        }
    }

    public boolean clearCache() throws Exception {
        if (olapConnection.isWrapperFor(RolapConnection.class)) {
            log.info("Clearing cache");
            RolapConnection rcon = olapConnection.unwrap(RolapConnection.class);
            rcon.getCacheControl(null).flushSchemaCache();
        }
        return true;
    }

    public String getDatasourceType() {
        return ISaikuConnection.OLAP_DATASOURCE;
    }

    public boolean initialized() {
        return initialized;
    }

    public Connection getConnection() {
        try {
            if (olapConnection.isClosed()) {
                connect();
            }
        } catch (Exception e) {
        }
        return olapConnection;
    }

    public void setProperties(Properties props) {
        properties = props;
    }

    public String getName() {
        return name;
    }

    public Properties getProperties() {
        return properties;
    }
}
