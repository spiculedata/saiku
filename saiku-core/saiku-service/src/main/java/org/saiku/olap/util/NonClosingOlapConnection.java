/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.olap.util;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKey;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.olap4j.OlapConnection;
import org.olap4j.OlapDatabaseMetaData;
import org.olap4j.OlapException;
import org.olap4j.OlapStatement;
import org.olap4j.PreparedOlapStatement;
import org.olap4j.Scenario;
import org.olap4j.mdx.parser.MdxParserFactory;
import org.olap4j.metadata.Catalog;
import org.olap4j.metadata.Database;
import org.olap4j.metadata.NamedList;
import org.olap4j.metadata.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A borrowed view of a <b>shared, cached</b> {@link OlapConnection} whose lifecycle calls are no-ops.
 *
 * <p>saiku#1969 (availability). The connection cache ({@code SecurityAwareConnectionManager}) owns
 * its connections and hands the SAME instance to every caller that resolves to the same cache key.
 * The XMLA endpoint is one such caller, and the XMLA server fork
 * ({@code olap4j-xmlaserver-1.3.0-spicule-jakarta}) treats the connection it is given as
 * per-request: {@code XmlaHandler.executeQuery} and {@code XmlaHandler.executeDrillThroughQuery}
 * both call {@code OlapConnection.close()} on their way out — on the failure path
 * ({@code 00HSBF01}/{@code 00HSBF02}) <em>and</em> on the success path. A bad MDX over {@code /xmla}
 * therefore closes the cached connection underneath every other caller sharing that cache slot, and
 * because the manager never re-opens or health-checks a closed connection, subsequent queries on
 * that datasource fail until something else happens to rebuild it.
 *
 * <p>Handing XMLA a per-request, non-cached connection instead would work but is not viable here:
 * it opens a new OLAP (and warehouse) session per XMLA call, and it does not help the
 * {@code security.enabled=false} datasources whose cached connection XMLA already shares with the
 * REST path. Making the manager detect {@code isClosed()} and rebuild is the complementary
 * half of the fix ({@code SecurityAwareConnectionManager}), but on its own it turns every XMLA
 * request into a fresh connect — XMLA's unconditional {@code close()} means the cached instance is
 * never usable twice.
 *
 * <p>So the ownership boundary is fixed here instead: XMLA gets a view that delegates every read and
 * write to the shared connection but refuses to {@code close()} (or {@code abort()}) it. The cached
 * connection stays open and usable for the next caller, and {@code close()} from the XMLA handler
 * becomes a logged no-op rather than a cross-caller outage.
 *
 * <p>Notes on the delegation:
 * <ul>
 *   <li>{@link #unwrap(Class)} delegates to the shared connection, so a caller that unwraps to the
 *       driver's connection still gets the real object — this is what keeps the fork's
 *       {@code OlapWrapper.unwrap} paths (unused by Saiku's servlet, but present in the jar)
 *       working against a proxy.</li>
 *   <li>{@link #isClosed()} DOES delegate: it must report the shared connection's true state, so a
 *       connection genuinely closed elsewhere is still visible as closed rather than masked.</li>
 *   <li>{@link #abort(Executor)} is a no-op for the same reason as {@code close()} — it destroys the
 *       connection for every other caller. No Saiku code path calls it; the fork does not either.</li>
 * </ul>
 */
public class NonClosingOlapConnection implements OlapConnection {

    private static final Logger log = LoggerFactory.getLogger(NonClosingOlapConnection.class);

    private final OlapConnection delegate;

    public NonClosingOlapConnection(OlapConnection delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate OlapConnection must not be null");
        }
        this.delegate = delegate;
    }

    /** The shared connection this view delegates to. */
    public OlapConnection getDelegate() {
        return delegate;
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle: the whole point of the class.
    // ------------------------------------------------------------------------------------------

    /**
     * No-op (saiku#1969). The connection belongs to the cache, not to the caller.
     *
     * <p>Logged at WARN so the fork's unconditional close stays visible in the logs without being
     * mistaken for a connection leak.
     */
    @Override
    public void close() throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug("saiku#1969: ignoring close() on a shared cached OLAP connection — the caller does not own it");
        }
    }

    /**
     * No-op, for the same reason as {@link #close()} — aborting kills the connection for every other
     * caller sharing this cache slot.
     */
    @Override
    public void abort(Executor executor) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug("saiku#1969: ignoring abort() on a shared cached OLAP connection");
        }
    }

    /** Delegates — the true state of the shared connection must stay visible. */
    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return !delegate.isClosed();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    // ------------------------------------------------------------------------------------------
    // Plain delegation.
    // ------------------------------------------------------------------------------------------

    @Override
    public OlapDatabaseMetaData getMetaData() throws OlapException {
        return delegate.getMetaData();
    }

    @Override
    public OlapStatement createStatement() throws OlapException {
        return delegate.createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return delegate.prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return delegate.prepareCall(sql);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        delegate.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        delegate.rollback();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws OlapException {
        delegate.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws OlapException {
        return delegate.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        delegate.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return delegate.createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return delegate.prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        delegate.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        delegate.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return delegate.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return delegate.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        delegate.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        delegate.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public CallableStatement prepareCall(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return delegate.prepareStatement(sql, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return delegate.prepareStatement(sql, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return delegate.prepareStatement(sql, columnNames);
    }

    @Override
    public Clob createClob() throws SQLException {
        return delegate.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return delegate.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return delegate.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        delegate.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        delegate.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return delegate.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return delegate.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return delegate.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws OlapException {
        delegate.setSchema(schema);
    }

    @Override
    public String getSchema() throws OlapException {
        return delegate.getSchema();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        delegate.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public void beginRequest() throws SQLException {
        delegate.beginRequest();
    }

    @Override
    public void endRequest() throws SQLException {
        delegate.endRequest();
    }

    @Override
    public boolean setShardingKeyIfValid(ShardingKey shardingKey, ShardingKey superShardingKey, int timeout)
            throws SQLException {
        return delegate.setShardingKeyIfValid(shardingKey, superShardingKey, timeout);
    }

    @Override
    public boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException {
        return delegate.setShardingKeyIfValid(shardingKey, timeout);
    }

    @Override
    public void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException {
        delegate.setShardingKey(shardingKey, superShardingKey);
    }

    @Override
    public void setShardingKey(ShardingKey shardingKey) throws SQLException {
        delegate.setShardingKey(shardingKey);
    }

    // ------------------------------------------------------------------------------------------
    // olap4j surface.
    // ------------------------------------------------------------------------------------------

    @Override
    public PreparedOlapStatement prepareOlapStatement(String mdx) throws OlapException {
        return delegate.prepareOlapStatement(mdx);
    }

    @Override
    public MdxParserFactory getParserFactory() {
        return delegate.getParserFactory();
    }

    @Override
    public String getDatabase() throws OlapException {
        return delegate.getDatabase();
    }

    @Override
    public void setDatabase(String databaseName) throws OlapException {
        delegate.setDatabase(databaseName);
    }

    @Override
    public Database getOlapDatabase() throws OlapException {
        return delegate.getOlapDatabase();
    }

    @Override
    public NamedList<Database> getOlapDatabases() throws OlapException {
        return delegate.getOlapDatabases();
    }

    @Override
    public Catalog getOlapCatalog() throws OlapException {
        return delegate.getOlapCatalog();
    }

    @Override
    public NamedList<Catalog> getOlapCatalogs() throws OlapException {
        return delegate.getOlapCatalogs();
    }

    @Override
    public Schema getOlapSchema() throws OlapException {
        return delegate.getOlapSchema();
    }

    @Override
    public NamedList<Schema> getOlapSchemas() throws OlapException {
        return delegate.getOlapSchemas();
    }

    @Override
    public void setLocale(Locale locale) {
        delegate.setLocale(locale);
    }

    @Override
    public Locale getLocale() {
        return delegate.getLocale();
    }

    @Override
    public void setRoleName(String roleName) throws OlapException {
        delegate.setRoleName(roleName);
    }

    @Override
    public String getRoleName() {
        return delegate.getRoleName();
    }

    @Override
    public List<String> getAvailableRoleNames() throws OlapException {
        return delegate.getAvailableRoleNames();
    }

    @Override
    public Scenario createScenario() throws OlapException {
        return delegate.createScenario();
    }

    @Override
    public void setScenario(Scenario scenario) throws OlapException {
        delegate.setScenario(scenario);
    }

    @Override
    public Scenario getScenario() throws OlapException {
        return delegate.getScenario();
    }

    @Override
    public String toString() {
        return "NonClosingOlapConnection(" + delegate + ")";
    }
}
