/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package mondrian.rolap;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import mondrian.olap.MondrianDef;
import mondrian.olap.Util;
import mondrian.util.ByteString;
import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMUtil;
import org.saiku.service.datasource.JdbcUrlPolicy;

/**
 * Converts a Mondrian&nbsp;3 (legacy) schema XML to a real Mondrian&nbsp;4
 * schema (<code>metamodelVersion="4.x"</code>, {@code <PhysicalSchema>},
 * {@code <MeasureGroups>}) using the engine's OWN
 * {@link RolapSchemaUpgrader#upgrade} — the exact code Mondrian&nbsp;4 runs
 * when it loads a legacy schema, so the output is faithful (calculated
 * members, annotations, degenerate dims, time hierarchies all preserved),
 * not a hand-rolled transform.
 *
 * <p>Lives in package {@code mondrian.rolap} because the upgrader's entry
 * points ({@code upgrade}, {@link SchemaKey}, {@link SchemaContentKey},
 * {@link ConnectionKey}, the {@code RolapSchemaLoader(null)} ctor) are
 * package-private. Exposes ONE public method so callers (the engine's
 * convert endpoint) can drive it by reflection without a compile-time
 * Mondrian dependency.
 *
 * <h2>Requires the tables to exist</h2>
 * The upgrader introspects JDBC metadata for every referenced table +
 * column (a missing table/column is fatal), so the target warehouse must
 * already contain the schema's tables with the right columns — rows are NOT
 * required. Callers get a typed {@link ConversionException}
 * ({@link Failure#TABLES_MISSING} / {@link Failure#CONNECTION_FAILED} /
 * {@link Failure#NOT_UPGRADABLE}) so they can 422 with an actionable message.
 */
public final class M3ToM4Converter {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(M3ToM4Converter.class);

    /** Why a conversion could not be produced. */
    public enum Failure {
        /** A table (or column) the schema references does not exist in the warehouse. */
        TABLES_MISSING,
        /**
         * The warehouse connection could not be opened at all (unreachable host,
         * wrong credentials, bad JDBC URL). Distinct from a schema problem — the
         * caller should fix the connection, not the schema.
         */
        CONNECTION_FAILED,
        /** The schema is malformed / uses a construct the upgrader rejects. */
        NOT_UPGRADABLE
    }

    /** Thrown when the M3 schema cannot be upgraded. */
    public static final class ConversionException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        ConversionException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }

        /** Machine token for the API body: {@code tables_missing} / {@code not_upgradable}. */
        public String token() {
            return failure.name().toLowerCase(Locale.ROOT);
        }
    }

    private M3ToM4Converter() {}

    /**
     * Convert legacy Mondrian-3 XML to Mondrian-4 XML, resolving physical
     * tables against the given warehouse connection.
     *
     * @param m3Xml    the Mondrian 3 (legacy) schema XML
     * @param jdbcUrl  the warehouse JDBC URL (drives dialect detection)
     * @param user     JDBC user (may be null — some URLs carry auth inline)
     * @param password JDBC password (may be null)
     * @return real Mondrian 4 schema XML
     * @throws ConversionException with a typed {@link Failure} on any failure
     */
    public static String convert(String m3Xml, String jdbcUrl, String user, String password)
            throws ConversionException {
        NonClosingSingleConnectionDataSource ds = null;
        try {
            // Opening the warehouse connection is part of the conversion, so
            // keep it inside the try: a connect failure (unreachable host, wrong
            // credentials, malformed URL) then classifies as a typed
            // CONNECTION_FAILED instead of escaping as an untyped 500.
            ds = new NonClosingSingleConnectionDataSource(jdbcUrl, user, password);

            Parser parser = XOMUtil.createDefaultParser();
            parser.setKeepPositions(true);
            DOMWrapper def = parser.parse(m3Xml);

            RolapSchemaLoader loader = new RolapSchemaLoader(null);
            Util.PropertyList connectInfo = Util.parseConnectString("Provider=mondrian;Jdbc=" + jdbcUrl);
            ByteString md5 = new ByteString(Util.digestMd5(m3Xml));
            SchemaKey key = new SchemaKey(
                    SchemaContentKey.create(connectInfo, "cloud:convert", m3Xml),
                    ConnectionKey.create(null, ds, "cloud:convert", null, null, null, null));

            MondrianDef.Schema m4 = RolapSchemaUpgrader.upgrade(loader, def, key, md5, connectInfo, ds, false);
            return m4.toXML();
        } catch (Throwable t) {
            throw classify(t);
        } finally {
            if (ds != null) {
                ds.closeUnderlying();
            }
        }
    }

    /** Map an upgrader failure to a typed {@link ConversionException}. */
    private static ConversionException classify(Throwable t) {
        // Walk the cause chain — the informative message can be nested.
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.toLowerCase(Locale.ROOT).contains("does not exist in database")) {
                LOG.info("M3->M4 conversion: referenced table missing ({})", m);
                return new ConversionException(
                        Failure.TABLES_MISSING,
                        "the schema references a table that does not exist in the warehouse",
                        t);
            }
        }
        // A failure to OPEN the warehouse connection — the datasource ctor wraps
        // the driver's SQLException in this exact IllegalStateException message.
        // Checked after TABLES_MISSING (a missing-relation error is more
        // specific) but before the NOT_UPGRADABLE fallback.
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("could not open warehouse connection")) {
                LOG.info("M3->M4 conversion: warehouse connection could not be opened ({})", m);
                return new ConversionException(
                        Failure.CONNECTION_FAILED, "could not connect to the warehouse to upgrade the schema", t);
            }
        }
        LOG.warn("M3->M4 conversion failed", t);
        return new ConversionException(Failure.NOT_UPGRADABLE, "the schema could not be upgraded to Mondrian 4", t);
    }

    /**
     * A {@link DataSource} that hands out ONE underlying connection, wrapped so
     * {@code close()} is a no-op — Mondrian opens + closes several connections
     * during a schema load, and (for a file-backed embedded DB like the test's
     * DuckDB) they must share the same session. A concrete class, so its
     * identity {@code hashCode}/{@code equals} satisfy Mondrian's
     * {@code DialectManager} WeakHashMap key requirement.
     */
    static final class NonClosingSingleConnectionDataSource implements DataSource {
        private final Connection shared;

        NonClosingSingleConnectionDataSource(String url, String user, String password) {
            try {
                Properties props = new Properties();
                if (user != null) {
                    props.put("user", user);
                }
                if (password != null) {
                    props.put("password", password);
                }
                // saiku#1902: same URL policy as every other DriverManager chokepoint.
                this.shared = JdbcUrlPolicy.openConnection(url, props);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("could not open warehouse connection for conversion", e);
            }
        }

        void closeUnderlying() {
            try {
                shared.close();
            } catch (java.sql.SQLException ignore) {
                // best-effort
            }
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Connection.class}, new NonClosingHandler(shared));
        }

        @Override
        public Connection getConnection(String username, String pwd) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }

    /** Delegates every Connection method except close(), which is a no-op. */
    private static final class NonClosingHandler implements InvocationHandler {
        private final Connection target;

        NonClosingHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                return null;
            }
            if ("isClosed".equals(method.getName())) {
                return Boolean.FALSE;
            }
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
