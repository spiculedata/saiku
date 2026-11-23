/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Property names are checked the way drivers read them: ASCII only, under Mondrian's
 * {@code jdbc.} forwarding prefix as well as without it, and H2 settings structurally (H2 runs
 * {@code SET <key> <value>} for each, so a value is an SQL expression). Legitimate datasource
 * shapes must keep passing.
 */
public class JdbcUrlPolicyKeyNormalisationTest {

    /** Nothing listens here; a URL that got past the policy fails to connect, it is never rejected. */
    private static final String CLOSED_PG = "jdbc:postgresql://127.0.0.1:1/db";

    private static final String ALIAS = "CREATE/**/ALIAS P_A FOR 'java.lang.Integer.bitCount'";

    /**
     * These cases are about property names, not where an H2 file lives, and they use paths from
     * several hosts; file-engine path confinement (saiku#2320) is covered by FileEngineConfinementTest.
     */
    @Before
    public void disablePathConfinement() {
        System.setProperty("saiku.jdbc.fileEngineConfinement", "false");
    }

    @After
    public void restorePathConfinement() {
        System.clearProperty("saiku.jdbc.fileEngineConfinement");
    }

    /* ------------------------------------------------------------------ accepts */

    @Test
    public void acceptsSeededAndDocumentedH2Shapes() {
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:/opt/saiku-home/data/foodmart;MODE=MySQL;"
                + "Catalog=file:/opt/saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver;JdbcUser=sa;");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc='jdbc:h2:C:/Users/me/saiku-home/data/foodmart;MODE=MySQL';"
                + "Catalog=file:C:/Users/me/saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver");
        JdbcUrlPolicy.validate("jdbc:h2:/opt/saiku-home/data/tpcds;MODE=PostgreSQL;AUTO_SERVER=TRUE");
        JdbcUrlPolicy.validate("jdbc:h2:mem:pharma;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=FALSE;");
        JdbcUrlPolicy.validate("jdbc:h2:mem:x;NON_KEYWORDS=VALUE,YEAR;TIME ZONE=Europe/London");
        JdbcUrlPolicy.validate("jdbc:h2:mem:x;SCHEMA=\"PUBLIC\";COLLATION=ENGLISH STRENGTH PRIMARY");
    }

    @Test
    public void acceptsNonAsciiPathsAndValues() {
        // A home directory or database name may be non-ASCII; only property names are restricted.
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:/home/jos\u00e9/saiku-home/data/foodmart;MODE=MySQL;"
                + "Catalog=file:/home/jos\u00e9/saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver");
        JdbcUrlPolicy.validate("jdbc:postgresql://db.example.com/entrep\u00f4t?ApplicationName=Caf\u00e9");
        JdbcUrlPolicy.validate("jdbc:duckdb:/data/donn\u00e9es.duckdb");
    }

    @Test
    public void acceptsOrdinaryWarehousesAndForwardedDriverProperties() {
        JdbcUrlPolicy.validate("jdbc:postgresql://db.example.com:5432/warehouse?ssl=true&sslmode=verify-full");
        JdbcUrlPolicy.validate("jdbc:mysql://db.example.com:3306/warehouse?useSSL=true&serverTimezone=UTC");
        JdbcUrlPolicy.validate("jdbc:duckdb:/data/warehouse.duckdb?autoload_known_extensions=true");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:postgresql://db.example.com/wh;Catalog=file:/x.xml;"
                + "JdbcDrivers=org.postgresql.Driver;jdbc.connectTimeout=10;jdbc.ApplicationName=saiku");
        JdbcUrlPolicy.validate(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;" + "jdbc.MODE=MySQL");
    }

    @Test
    public void infoMapAcceptsCredentialsForH2AndOtherDrivers() throws Exception {
        Properties creds = new Properties();
        creds.setProperty("user", "sa");
        creds.setProperty("password", "p(\u00e4ss)'w;rd");
        creds.setProperty("MODE", "MySQL");
        try (Connection c = JdbcUrlPolicy.openConnection("jdbc:h2:mem:keynorm-accept", creds)) {
            assertTrue(c.isValid(1));
        }
        JdbcUrlPolicy.rejectDeniedInfoProperties("jdbc:postgresql://h/db", creds);
    }

    @Test
    public void acceptsNonAsciiTextThatIsNotAPropertyName() {
        // Text before an '=' inside a value, a path or an inline schema is not a property name.
        JdbcUrlPolicy.validate("jdbc:postgresql://db/wh?user=u&password=\u043f\u0430\u0440\u043e\u043b\u044c=1");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:postgresql://db/wh;Catalog=file:/x.xml;JdbcUser=u;"
                + "JdbcPassword=Gr\u00f6\u00dfe=42;");
        JdbcUrlPolicy.validate(
                "jdbc:mondrian:Jdbc=jdbc:postgresql://db/wh;Catalog=file:/donn\u00e9es/ann\u00e9e=2024/s.xml");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:postgresql://db/wh;CatalogContent=<Schema name='S'>"
                + "<Cube name='C'><CalculatedMember name='x' formula='[Measures].[Co\u00fbt] = 1'/></Cube></Schema>");
    }

    @Test
    public void acceptsMondrianPropertiesAfterAnH2Jdbc() {
        // Mondrian ends an unquoted Jdbc value at the first ';', so these are Mondrian's, not H2's.
        String h2 = "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;";
        JdbcUrlPolicy.validate(h2 + "JdbcSchema='My Schema';Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver");
        JdbcUrlPolicy.validate(h2 + "session.region=EMEA (north);Catalog=file:/x.xml");
        JdbcUrlPolicy.validate(h2 + "MODE=MySQL;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;JdbcUser=sa;"
                + "JdbcPassword='p;x(1)';");
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:C:\\Users\\me\\saiku-home/data/foodmart;MODE=MySQL;"
                + "Catalog=file:C:\\Users\\me\\saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver;JdbcUser=sa;");
    }

    /* ------------------------------------------------------------------ property names must be ASCII */

    @Test
    public void rejectsNonAsciiPropertyNamesThatFoldToDeniedKeys() {
        assertRejected("jdbc:h2:mem:t;\u0131n\u0131t=SELECT 1", "ASCII");
        assertRejected("jdbc:postgresql://h/db?\u017focketFactory=x", "ASCII");
        assertRejected("jdbc:mysql://h/db?autoDeser\u0131alize=true", "ASCII");
        assertRejected("jdbc:mysql://h/db?autoDeser%C4%B1alize=true", "ASCII");
    }

    @Test
    public void rejectsNonAsciiPropertyNamesInsideMondrianWrappers() {
        assertRejected(
                "jdbc:mondrian:Jdbc='jdbc:h2:mem:t;\u0131n\u0131t=SELECT 1';Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver",
                "ASCII");
        assertRejected(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;jdbc.\u0131n\u0131t=SELECT 1",
                "ASCII");
    }

    /** The full shapes, each rule alone would stop them; any policy reason is acceptable. */
    @Test
    public void rejectsCombinedFoldedKeyAndCommentedStatement() {
        assertRejected("jdbc:h2:mem:t;\u0131n\u0131t=" + ALIAS, "");
        assertRejected("jdbc:h2:mem:t;CACHE_SIZE=8192\\;" + ALIAS, "");
        assertRejected(
                "jdbc:mondrian:Jdbc='jdbc:h2:mem:t;\u0131n\u0131t=CREATE/**/ALIAS P_A FOR "
                        + "''java.lang.Integer.bitCount''';Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver",
                "");
        assertRejected(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;jdbc.\u0131n\u0131t="
                        + ALIAS,
                "");
    }

    @Test
    public void infoMapRejectsNonAsciiNames() {
        Properties info = new Properties();
        info.setProperty("\u0131n\u0131t", "SELECT 1");
        assertInfoRejected("jdbc:h2:mem:t", info, "ASCII");
        assertInfoRejected(CLOSED_PG, info, "ASCII");
    }

    /* ------------------------------------------------------------------ H2 settings */

    @Test
    public void rejectsH2RunOnConnectKeysWhateverTheValue() {
        assertRejected("jdbc:h2:mem:t;INIT=", "INIT=");
        assertRejected("jdbc:h2:mem:t;DATABASE_EVENT_LISTENER=com.example.Listener", "databaseeventlistener");
        Properties info = new Properties();
        info.setProperty("INIT", "");
        assertInfoRejected("jdbc:h2:mem:t", info, "INIT=");
        Properties listener = new Properties();
        listener.setProperty("DATABASE_EVENT_LISTENER", "com.example.Listener");
        assertInfoRejected("jdbc:h2:mem:t", listener, "databaseeventlistener");
    }

    @Test
    public void rejectsEscapedSeparatorsInH2Settings() {
        assertRejected("jdbc:h2:mem:t;CACHE_SIZE=8192\\;SET X 1", "escape");
        assertRejected("jdbc:h2:mem:t;MODE=MySQL\\;SET X 1", "escape");
    }

    @Test
    public void rejectsExpressionsInH2SettingValues() {
        assertRejected("jdbc:h2:mem:t;CACHE_SIZE=LENGTH(FILE_READ('/etc/hostname'))", "plain tokens");
        assertRejected("jdbc:mondrian:Jdbc='jdbc:h2:mem:t;CACHE_SIZE=LENGTH(X())';Catalog=file:/x.xml", "plain tokens");
        Properties info = new Properties();
        info.setProperty("CACHE_SIZE", "LENGTH(X())");
        assertInfoRejected("jdbc:h2:mem:t", info, "plain tokens");
    }

    @Test
    public void rejectsH2TokensSplitBySqlComments() {
        assertRejected("jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;Role=CREATE/**/ALIAS", "CREATE ALIAS");
        assertRejected("jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;Role=CREATE/**/TRIGGER", "TRIGGER");
    }

    @Test
    public void rejectsH2SettingsThatNameAClass() {
        assertRejected("jdbc:h2:mem:t;DEFAULT_TABLE_ENGINE=com.example.Engine", "defaulttableengine");
        assertRejected(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;"
                        + "jdbc.DEFAULT_TABLE_ENGINE=com.example.Engine",
                "defaulttableengine");
        assertInfoRejected("jdbc:h2:mem:t", info("DEFAULT_TABLE_ENGINE", "com.example.Engine"), "defaulttableengine");
    }

    @Test
    public void infoMapRejectsNonStringEntries() {
        Properties p = new Properties();
        p.put("INIT", new StringBuilder("SELECT 1"));
        assertInfoRejected("jdbc:h2:mem:keynorm-nonstring", p, "strings");
    }

    /* ------------------------------------------------------------------ Mondrian connect-string quoting */

    @Test
    public void readsWrapperQuotingTheWayMondrianDoes() {
        // A doubled quote is an escaped quote to Mondrian, so the H2 URL runs to the closing quote.
        String tail = ";Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver";
        assertRejected("jdbc:mondrian:Jdbc='jdbc:h2:mem:x'';Role=x;CACHE_SIZE=LENGTH(X())'" + tail, "plain tokens");
        assertRejected("jdbc:mondrian:Jdbc=\"jdbc:h2:mem:x\"\";Role=x;CACHE_SIZE=LENGTH(X())\"" + tail, "plain tokens");
        assertRejected("jdbc:mondrian:Jdbc='jdbc:h2:mem:x'';Role=x;CACHE_SIZE=1\\;SET X 1'" + tail, "escape");
        assertRejected(
                "jdbc:mondrian:Jdbc='jdbc:h2:mem:x'';Role=x;DEFAULT_TABLE_ENGINE=com.example.Engine'" + tail,
                "defaulttableengine");
    }

    @Test
    public void rejectsUnparseableWrapper() {
        assertRejected("jdbc:mondrian:Jdbc='jdbc:h2:mem:x;Catalog=file:/x.xml", "malformed Mondrian");
    }

    /* ------------------------------------------------------------------ Mondrian forwarding prefix */

    @Test
    public void rejectsDeniedKeysUnderTheMondrianForwardingPrefix() {
        String base = "jdbc:mondrian:Jdbc=jdbc:mysql://h/db;Catalog=file:/x.xml;";
        assertRejected(base + "jdbc.socketFactory=x", "socketfactory");
        assertRejected(base + "jdbc.sslfactory=x", "sslfactory");
        assertRejected(base + "jdbc.autoDeserialize=true", "autodeserialize");
        assertRejected(base + "jdbc.queryInterceptors=x", "queryinterceptors");
        assertRejected(base + "jdbc.allowLoadLocalInfile=true", "allowloadlocalinfile");
        assertRejected(base + "JDBC.JAVA_OBJECT_SERIALIZER=x", "javaobjectserializer");
    }

    @Test
    public void appliesH2SettingRulesToForwardedProperties() {
        String base = "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;";
        assertRejected(base + "jdbc.INIT=SELECT 1", "INIT=");
        assertRejected(base + "jdbc.DATABASE_EVENT_LISTENER=com.example.Listener", "databaseeventlistener");
        assertRejected(base + "jdbc.CACHE_SIZE=LENGTH(X())", "plain tokens");
    }

    @Test
    public void infoMapOnMondrianWrapperIsHeldToTheWrapperRules() {
        String wrapper = "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/nonexistent/x.xml;JdbcDrivers=org.h2.Driver";
        assertInfoRejected(wrapper, info("DynamicSchemaProcessor", "com.example.Processor"), "DynamicSchemaProcessor");
        assertInfoRejected(wrapper, info("Dialect", "com.example.Dialect"), "Dialect=");
        assertInfoRejected(wrapper, info("Jdbc", "jdbc:postgresql://h/db?socketFactory=x"), "socketfactory");
        assertInfoRejected(wrapper, info("DataSource", "ldap://h/x"), "JNDI");
        assertInfoRejected(wrapper, info("jdbc.CACHE_SIZE", "LENGTH(X())"), "plain tokens");
        assertInfoRejected(wrapper, info("jdbc.socketFactory", "x"), "socketfactory");
    }

    @Test
    public void infoMapOnMondrianWrapperAcceptsCredentials() {
        Properties creds = info("JdbcUser", "sa");
        creds.setProperty("JdbcPassword", "p(äss);w");
        creds.setProperty("user", "sa");
        JdbcUrlPolicy.rejectDeniedInfoProperties(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver", creds);
    }

    private static Properties info(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    /* ------------------------------------------------------------------ other drivers' hooks */

    @Test
    public void rejectsMondrianDialectClassName() {
        assertRejected("jdbc:mondrian:Jdbc=jdbc:h2:mem:t;Catalog=file:/x.xml;Dialect=com.example.Dialect", "Dialect=");
    }

    @Test
    public void rejectsDuckDbSessionInitFile() {
        assertRejected("jdbc:duckdb:/data/wh.duckdb?session_init_sql_file=/tmp/x.sql", "sessioninitsqlfile");
        assertRejected("jdbc:quack:/data/wh.duckdb?SESSION_INIT_SQL_FILE=/tmp/x.sql", "sessioninitsqlfile");
        Properties info = new Properties();
        info.setProperty("session_init_sql_file", "/tmp/x.sql");
        assertInfoRejected(CLOSED_PG, info, "sessioninitsqlfile");
    }

    /* ------------------------------------------------------------------ helpers */

    private static void assertRejected(String url, String fragment) {
        try {
            JdbcUrlPolicy.validate(url);
            fail("expected rejection");
        } catch (IllegalArgumentException e) {
            assertReason(e, fragment);
        }
    }

    /** Through the public entry point: a rejection must come from the policy, before the driver. */
    private static void assertInfoRejected(String url, Properties info, String fragment) {
        try (Connection ignored = JdbcUrlPolicy.openConnection(url, info)) {
            fail("expected rejection, but the connection opened");
        } catch (IllegalArgumentException e) {
            assertReason(e, fragment);
        } catch (java.sql.SQLException e) {
            fail("expected rejection by the policy, but the driver was reached: "
                    + e.getClass().getSimpleName());
        }
    }

    private static void assertReason(IllegalArgumentException e, String fragment) {
        String msg = e.getMessage();
        assertTrue("unexpected rejection: " + msg, msg.contains("Invalid datasource JDBC URL"));
        assertTrue(
                "reason should mention '" + fragment + "', was: " + msg,
                msg.toLowerCase().contains(fragment.toLowerCase()));
    }
}
