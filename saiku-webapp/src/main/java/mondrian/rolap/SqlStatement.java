/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2007-2013 Pentaho and others
// All Rights Reserved.
*/
package mondrian.rolap;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import mondrian.olap.MondrianProperties;
import mondrian.olap.Util;
import mondrian.server.Execution;
import mondrian.server.Locus;
import mondrian.server.monitor.*;
import mondrian.server.monitor.SqlStatementEvent.Purpose;
import mondrian.util.*;
// mondrian-saiku 4.8.1.9 retyped RolapUtil.SQL_LOGGER / .LOGGER from
// org.apache.log4j.Logger to org.slf4j.Logger as part of its log4j-1.x
// drop. This override sits in the same package so it accesses both
// fields directly — keep the import in lock-step with upstream.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SqlStatement contains a SQL statement and associated resources throughout
 * its lifetime.
 *
 * <p>The goal of SqlStatement is to make tracing, error-handling and
 * resource-management easier. None of the methods throws a SQLException;
 * if an error occurs in one of the methods, the method wraps the exception
 * in a {@link RuntimeException} describing the high-level operation, logs
 * that the operation failed, and throws that RuntimeException.
 *
 * <p>If methods succeed, the method generates lifecycle logging such as
 * the elapsed time and number of rows fetched.
 *
 * <p>There are a few obligations on the caller. The caller must:<ul>
 * <li>call the {@link #handle(Throwable)} method if one of the contained
 *     objects (say the {@link java.sql.ResultSet}) gives an error;
 * <li>call the {@link #close()} method if all operations complete
 *     successfully.
 * <li>increment the {@link #rowCount} field each time a row is fetched.
 * </ul>
 *
 * <p>The {@link #close()} method is idempotent. You are welcome to call it
 * more than once.
 *
 * <p>SqlStatement is not thread-safe.
 *
 * @author jhyde
 * @since 2.3
 */
public class SqlStatement implements DBStatement {
    private static final Logger LOG = LoggerFactory.getLogger(SqlStatement.class);
    private static final String TIMING_NAME = "SqlStatement-";

    // used for SQL logging, allows for a SQL Statement UID
    private static final AtomicLong ID_GENERATOR = new AtomicLong();

    private static final Semaphore querySemaphore =
            new Semaphore(MondrianProperties.instance().QueryLimit.get(), true);

    private final DataSource dataSource;
    private Connection jdbcConnection;
    private ResultSet resultSet;
    private final String sql;
    private final List<Type> types;
    private final int maxRows;
    private final int firstRowOrdinal;
    private final Locus locus;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private boolean haveSemaphore;
    public int rowCount;
    private long startTimeNanos;
    private long startTimeMillis;
    private final Map<Object, Accessor> accessors = new HashMap<Object, Accessor>();
    private State state = State.FRESH;
    private final long id;
    private Util.Function1<Statement, Void> callback;

    /**
     * Creates a SqlStatement.
     *
     * @param dataSource Data source
     * @param sql SQL
     * @param types Suggested types of columns, or null;
     *     if present, must have one element for each SQL column;
     *     each not-null entry overrides deduced JDBC type of the column
     * @param maxRows Maximum rows; <= 0 means no maximum
     * @param firstRowOrdinal Ordinal of first row to skip to; <= 0 do not skip
     * @param locus Execution context of this statement
     * @param resultSetType Result set type
     * @param resultSetConcurrency Result set concurrency
     */
    public SqlStatement(
            DataSource dataSource,
            String sql,
            List<Type> types,
            int maxRows,
            int firstRowOrdinal,
            Locus locus,
            int resultSetType,
            int resultSetConcurrency,
            Util.Function1<Statement, Void> callback) {
        this.callback = callback;
        this.id = ID_GENERATOR.getAndIncrement();
        this.dataSource = dataSource;
        this.sql = sql;
        this.types = types;
        this.maxRows = maxRows;
        this.firstRowOrdinal = firstRowOrdinal;
        this.locus = locus;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
    }

    /**
     * Executes the current statement, and handles any SQLException.
     */
    public void execute() {
        assert state == State.FRESH : "cannot re-execute";
        state = State.ACTIVE;
        Counters.SQL_STATEMENT_EXECUTE_COUNT.incrementAndGet();
        Counters.SQL_STATEMENT_EXECUTING_IDS.add(id);
        String status = "failed";
        Statement statement = null;
        try {
            // Check execution state
            locus.execution.checkCancelOrTimeout();

            this.jdbcConnection = dataSource.getConnection();
            querySemaphore.acquire();
            haveSemaphore = true;
            // Trace start of execution.
            if (RolapUtil.SQL_LOGGER.isDebugEnabled()) {
                StringBuilder sqllog = new StringBuilder();
                sqllog.append(id).append(": ").append(locus.component).append(": executing sql [");
                if (sql.indexOf('\n') >= 0) {
                    // SQL appears to be formatted as multiple lines. Make it
                    // start on its own line.
                    sqllog.append("\n");
                }
                sqllog.append(sql);
                sqllog.append(']');
                RolapUtil.SQL_LOGGER.debug(sqllog.toString());
            }

            // Execute hook.
            RolapUtil.ExecuteQueryHook hook = RolapUtil.getHook();
            if (hook != null) {
                hook.onExecuteQuery(sql);
            }

            // Check execution state
            locus.execution.checkCancelOrTimeout();

            startTimeNanos = System.nanoTime();
            startTimeMillis = System.currentTimeMillis();

            if (resultSetType < 0 || resultSetConcurrency < 0) {
                statement = jdbcConnection.createStatement();
            } else {
                statement = jdbcConnection.createStatement(resultSetType, resultSetConcurrency);
            }
            if (maxRows > 0) {
                statement.setMaxRows(maxRows);
            }

            // First make sure to register with the execution instance.
            if (getPurpose() != Purpose.CELL_SEGMENT) {
                locus.execution.registerStatement(locus, statement);
            } else {
                if (callback != null) {
                    callback.apply(statement);
                }
            }

            locus.getServer()
                    .getMonitor()
                    .sendEvent(new SqlStatementStartEvent(
                            startTimeMillis, id, locus, sql, getPurpose(), getCellRequestCount()));

            this.resultSet = statement.executeQuery(sql);

            // skip to first row specified in request
            this.state = State.ACTIVE;
            if (firstRowOrdinal > 0) {
                if (resultSetType == ResultSet.TYPE_FORWARD_ONLY) {
                    for (int i = 0; i < firstRowOrdinal; ++i) {
                        if (!this.resultSet.next()) {
                            this.state = State.DONE;
                            break;
                        }
                    }
                } else {
                    if (!this.resultSet.absolute(firstRowOrdinal)) {
                        this.state = State.DONE;
                    }
                }
            }

            long timeMillis = System.currentTimeMillis();
            long timeNanos = System.nanoTime();
            final long executeNanos = timeNanos - startTimeNanos;
            final long executeMillis = executeNanos / 1000000;
            Util.addDatabaseTime(executeMillis);
            status = ", exec " + executeMillis + " ms";

            locus.getServer()
                    .getMonitor()
                    .sendEvent(new SqlStatementExecuteEvent(timeMillis, id, locus, sql, getPurpose(), executeNanos));

            // Compute accessors. They ensure that we use the most efficient
            // method (e.g. getInt, getDouble, getObject) for the type of the
            // column. Even if you are going to box the result into an object,
            // it is better to use getInt than getObject; the latter might
            // return something daft like a BigDecimal (does, on the Oracle JDBC
            // driver).
            accessors.clear();
            Integer index = 0;
            for (Type type : guessTypes()) {
                // REVIEW: Is caching always needed? Some drivers don't need it;
                //   some columns are only used once.
                final boolean caching = true;
                accessors.put(index, createAccessor(accessors.size(), type, caching));
                index++;
            }
        } catch (Throwable e) {
            status = ", failed (" + e + ")";

            // This statement was leaked to us. It is our responsibility
            // to dispose of it.
            Util.close(null, statement, null);

            // Now handle this exception.
            throw handle(e);
        } finally {
            RolapUtil.SQL_LOGGER.debug(id + ": " + status);

            if (RolapUtil.LOGGER.isDebugEnabled()) {
                RolapUtil.LOGGER.debug(locus.component + ": executing sql [" + sql + "]" + status);
            }
        }
    }

    /**
     * Closes all resources (statement, result set) held by this
     * SqlStatement.
     *
     * <p>If any of them fails, wraps them in a
     * {@link RuntimeException} describing the high-level operation which
     * this statement was performing. No further error-handling is required
     * to produce a descriptive stack trace, unless you want to absorb the
     * error.</p>
     *
     * <p>This method is idempotent.</p>
     */
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;

        if (haveSemaphore) {
            haveSemaphore = false;
            querySemaphore.release();
        }

        // According to the JDBC spec, closing a statement automatically closes
        // its result sets, and closing a connection automatically closes its
        // statements. But let's be conservative and close everything
        // explicitly.
        SQLException ex = Util.close(resultSet, null, jdbcConnection);
        resultSet = null;
        jdbcConnection = null;

        if (ex != null) {
            throw Util.newError(ex, locus.message + "; sql=[" + sql + "]");
        }

        long endTime = System.currentTimeMillis();
        long totalMs = endTime - startTimeMillis;
        String status = ", exec+fetch " + totalMs + " ms, " + rowCount + " rows";

        locus.execution.getQueryTiming().markFull(TIMING_NAME + locus.component, totalMs);

        RolapUtil.SQL_LOGGER.debug(id + ": " + status);

        Counters.SQL_STATEMENT_CLOSE_COUNT.incrementAndGet();
        boolean remove = Counters.SQL_STATEMENT_EXECUTING_IDS.remove(id);
        status += ", ex=" + Counters.SQL_STATEMENT_EXECUTE_COUNT.get()
                + ", close=" + Counters.SQL_STATEMENT_CLOSE_COUNT.get()
                + ", open=" + Counters.SQL_STATEMENT_EXECUTING_IDS;

        if (RolapUtil.LOGGER.isDebugEnabled()) {
            RolapUtil.LOGGER.debug(locus.component + ": done executing sql [" + sql + "]" + status);
        }

        if (!remove) {
            throw new AssertionError("SqlStatement closed that was never executed: " + id);
        }

        locus.getServer()
                .getMonitor()
                .sendEvent(new SqlStatementEndEvent(endTime, id, locus, sql, getPurpose(), rowCount, false, null));
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    /**
     * Handles an exception thrown from the ResultSet, implicitly calls
     * {@link #close}, and returns an exception which includes the full
     * stack, including a description of the high-level operation.
     *
     * @param e Exception
     * @return Runtime exception
     */
    public RuntimeException handle(Throwable e) {
        RuntimeException runtimeException = Util.newError(e, locus.message + "; sql=[" + sql + "]");
        try {
            close();
        } catch (Throwable t) {
            // ignore
        }
        return runtimeException;
    }

    /**
     * Creates an accessor that returns the value of a given column, converting
     * to the required type.
     *
     * <p>Caching is necessary on JDBC drivers (e.g. sun's JDBC-ODBC bridge)
     * that only allow you to get the value of a column once per row.
     *
     * @param column Column ordinal (0-based)
     * @param type Desired type
     * @param caching Whether accessor should cache value for if the same
     *     column's value is accessed more than once on the same row
     * @return Value
     */
    private Accessor createAccessor(int column, Type type, boolean caching) {
        if (caching) {
            final Accessor accessor = createAccessor(column, type, false);
            return new Accessor() {
                int lastRowCount = -1;
                Comparable lastValue;

                public Comparable get() throws SQLException {
                    if (SqlStatement.this.rowCount > lastRowCount) {
                        lastValue = accessor.get();
                        lastRowCount = SqlStatement.this.rowCount;
                    }
                    return lastValue;
                }
            };
        }
        final int columnPlusOne = column + 1;
        switch (type) {
            case OBJECT:
                return new Accessor() {
                    public Comparable get() throws SQLException {
                        return (Comparable) resultSet.getObject(columnPlusOne);
                    }
                };
            case STRING:
                return new Accessor() {
                    public Comparable get() throws SQLException {
                        return resultSet.getString(columnPlusOne);
                    }
                };
            case INT:
                return new Accessor() {
                    public Comparable get() throws SQLException {
                        final int val = resultSet.getInt(columnPlusOne);
                        if (val == 0 && resultSet.wasNull()) {
                            return null;
                        }
                        return val;
                    }
                };
            case LONG:
                return new Accessor() {
                    public Comparable get() throws SQLException {
                        final long val = resultSet.getLong(columnPlusOne);
                        if (val == 0 && resultSet.wasNull()) {
                            return null;
                        }
                        return val;
                    }
                };
            case DOUBLE:
                return new Accessor() {
                    public Comparable get() throws SQLException {
                        final double val = resultSet.getDouble(columnPlusOne);
                        if (val == 0 && resultSet.wasNull()) {
                            return null;
                        }
                        return val;
                    }
                };
            default:
                throw Util.unexpected(type);
        }
    }

    public List<Type> guessTypes() throws SQLException {
        final ResultSetMetaData metaData = resultSet.getMetaData();
        final int columnCount = metaData.getColumnCount();
        assert this.types == null || this.types.size() == columnCount
                : "types " + types + " cardinality != column count " + columnCount;
        List<Type> types = new ArrayList<Type>();
        for (int i = 0; i < columnCount; i++) {
            final Type suggestedType = this.types == null ? null : this.types.get(i);
            // There might not be a schema constructed yet,
            // so watch out here for NPEs.
            RolapSchema schema = locus.execution
                    .getMondrianStatement()
                    .getMondrianConnection()
                    .getSchema();

            if (suggestedType != null) {
                types.add(suggestedType);
            } else if (schema != null && schema.getDialect() != null) {
                types.add(schema.getDialect().getType(metaData, i));
            } else {
                types.add(Type.OBJECT);
            }
        }
        return types;
    }

    public Map<Object, Accessor> getAccessors() throws SQLException {
        return accessors;
    }

    /**
     * Returns the result set in a proxy which automatically closes this
     * SqlStatement (and hence also the statement and result set) when the
     * result set is closed.
     *
     * <p>This helps to prevent connection leaks. The caller still has to
     * remember to call ResultSet.close(), of course.
     *
     * @return Wrapped result set
     */
    public ResultSet getWrappedResultSet() {
        // Saiku patch (JDK 17+ / modules): passing `null` as the loader fails
        // because java.sql.ResultSet lives in the `java.sql` module which is
        // not visible from the bootstrap loader. Use the current thread's
        // context classloader (falling back to ResultSet's own loader and then
        // the system classloader) so the generated proxy class can reference
        // the ResultSet interface at link time.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ResultSet.class.getClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        return (ResultSet) Proxy.newProxyInstance(
                loader, new Class<?>[] {ResultSet.class}, new MyDelegatingInvocationHandler(this));
    }

    private SqlStatementEvent.Purpose getPurpose() {
        if (locus instanceof StatementLocus) {
            return ((StatementLocus) locus).purpose;
        } else {
            return SqlStatementEvent.Purpose.OTHER;
        }
    }

    private int getCellRequestCount() {
        if (locus instanceof StatementLocus) {
            return ((StatementLocus) locus).cellRequestCount;
        } else {
            return 0;
        }
    }

    /**
     * The approximate JDBC type of a column.
     *
     * <p>This type affects which {@link ResultSet} method we use to get values
     * of this column: the default is {@link java.sql.ResultSet#getObject(int)},
     * but we'd prefer to use native values {@code getInt} and {@code getDouble}
     * if possible.
     */
    public enum Type {
        OBJECT,
        DOUBLE,
        INT,
        LONG,
        STRING;

        public Object get(ResultSet resultSet, int column) throws SQLException {
            switch (this) {
                case OBJECT:
                    return resultSet.getObject(column + 1);
                case STRING:
                    return resultSet.getString(column + 1);
                case INT:
                    return resultSet.getInt(column + 1);
                case LONG:
                    return resultSet.getLong(column + 1);
                case DOUBLE:
                    return resultSet.getDouble(column + 1);
                default:
                    throw Util.unexpected(this);
            }
        }
    }

    public interface Accessor {
        Comparable get() throws SQLException;
    }

    /**
     * Reflectively implements the {@link ResultSet} interface by routing method
     * calls to the result set inside a {@link mondrian.rolap.SqlStatement}.
     * When the result set is closed, so is the SqlStatement, and hence the
     * JDBC connection and statement also.
     */
    // must be public for reflection to work
    public static class MyDelegatingInvocationHandler extends DelegatingInvocationHandler {
        private final SqlStatement sqlStatement;

        /**
         * Creates a MyDelegatingInvocationHandler.
         *
         * @param sqlStatement SQL statement
         */
        MyDelegatingInvocationHandler(SqlStatement sqlStatement) {
            this.sqlStatement = sqlStatement;
        }

        protected Object getTarget() {
            return sqlStatement.getResultSet();
        }

        /**
         * Helper method to implement {@link java.sql.ResultSet#close()}.
         *
         * @throws SQLException on error
         */
        public void close() throws SQLException {
            sqlStatement.close();
        }
    }

    private enum State {
        FRESH,
        ACTIVE,
        DONE,
        CLOSED
    }

    public static class StatementLocus extends Locus {
        private final SqlStatementEvent.Purpose purpose;
        private final int cellRequestCount;

        public StatementLocus(
                Execution execution,
                String component,
                String message,
                SqlStatementEvent.Purpose purpose,
                int cellRequestCount) {
            super(execution, component, message);
            this.purpose = purpose;
            this.cellRequestCount = cellRequestCount;
        }
    }
}

// End SqlStatement.java
