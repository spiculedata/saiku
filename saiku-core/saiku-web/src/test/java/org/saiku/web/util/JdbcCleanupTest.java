package org.saiku.web.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Regression coverage for the drillthrough resource-cleanup contract:
 * <ul>
 *   <li>Both the {@link ResultSet} and its parent {@link Statement} must be closed.</li>
 *   <li>An exception from {@link ResultSet#getStatement()} must not suppress {@code rs.close()}.</li>
 *   <li>Cleanup must never throw out of a {@code finally} block, otherwise it shadows the
 *       real query failure that surfaced in the {@code try}.</li>
 * </ul>
 */
public class JdbcCleanupTest {

    @Test
    public void closeQuietly_closes_resultset_and_statement() throws Exception {
        AtomicBoolean rsClosed = new AtomicBoolean();
        AtomicBoolean stmtClosed = new AtomicBoolean();
        Statement stmt = stubStatement(stmtClosed, false);
        ResultSet rs = stubResultSet(rsClosed, stmt, false);

        JdbcCleanup.closeQuietly(rs);

        assertTrue("ResultSet.close() must be called", rsClosed.get());
        assertTrue("Statement.close() must be called", stmtClosed.get());
    }

    @Test
    public void closeQuietly_swallows_getStatement_failure() throws Exception {
        AtomicBoolean rsClosed = new AtomicBoolean();
        ResultSet rs = stubResultSet(rsClosed, null, /*throwOnGetStatement*/ true);

        // Must not throw — and rs.close() must still be invoked despite the prior failure.
        JdbcCleanup.closeQuietly(rs);

        assertTrue("ResultSet.close() must still be called when getStatement() throws", rsClosed.get());
    }

    @Test
    public void closeQuietly_swallows_close_failures() throws Exception {
        AtomicBoolean stmtClosed = new AtomicBoolean();
        AtomicBoolean rsClosed = new AtomicBoolean();
        Statement stmt = stubStatement(stmtClosed, /*throwOnClose*/ true);
        ResultSet rs = stubResultSet(rsClosed, stmt, /*throwOnGetStatement*/ false, /*throwOnClose*/ true);

        // Must not throw out of cleanup even when both closes blow up.
        JdbcCleanup.closeQuietly(rs);

        // Best-effort: both close() entries were attempted.
        assertTrue("ResultSet.close() must be attempted", rsClosed.get());
        assertTrue("Statement.close() must be attempted", stmtClosed.get());
    }

    @Test
    public void closeQuietly_null_is_noop() {
        // Must not throw on a null ResultSet (drillthrough sometimes never assigns one).
        JdbcCleanup.closeQuietly(null);
        assertFalse("trivially passes — included so the null branch is explicit", false);
    }

    // --- stub factories ---------------------------------------------------------

    private static ResultSet stubResultSet(AtomicBoolean closed, Statement parent, boolean throwOnGetStatement) {
        return stubResultSet(closed, parent, throwOnGetStatement, false);
    }

    private static ResultSet stubResultSet(
            AtomicBoolean closed, Statement parent, boolean throwOnGetStatement, boolean throwOnClose) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "close":
                    closed.set(true);
                    if (throwOnClose) {
                        throw new SQLException("simulated rs.close failure");
                    }
                    return null;
                case "getStatement":
                    if (throwOnGetStatement) {
                        throw new SQLException("simulated rs.getStatement failure");
                    }
                    return parent;
                case "isClosed":
                    return closed.get();
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "StubResultSet";
                default:
                    return defaultReturn(method);
            }
        };
        return (ResultSet)
                Proxy.newProxyInstance(JdbcCleanupTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, h);
    }

    private static Statement stubStatement(AtomicBoolean closed, boolean throwOnClose) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "close":
                    closed.set(true);
                    if (throwOnClose) {
                        throw new SQLException("simulated stmt.close failure");
                    }
                    return null;
                case "isClosed":
                    return closed.get();
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "StubStatement";
                default:
                    return defaultReturn(method);
            }
        };
        return (Statement)
                Proxy.newProxyInstance(JdbcCleanupTest.class.getClassLoader(), new Class<?>[] {Statement.class}, h);
    }

    private static Object defaultReturn(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) return false;
        if (rt == byte.class || rt == short.class || rt == int.class || rt == long.class) return 0;
        if (rt == float.class || rt == double.class) return 0d;
        if (rt == char.class) return '\0';
        return null;
    }
}
