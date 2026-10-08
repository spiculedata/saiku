/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.olap.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * saiku#1914: the guardrails themselves. Every limit must have a safe default and must
 * survive a hostile or broken config — a typo in {@code saiku.properties} must not
 * silently restore the unbounded behaviour this class exists to remove.
 *
 * <p>{@code Statement} is faked with a {@link Proxy} rather than an anonymous subclass:
 * the interface has ~50 methods, and this project still ships mockito-all 1.8.5 which
 * cannot instrument classes on JDK 17+.
 */
public class QueryGuardrailsTest {

    @After
    public void clearOverrides() {
        System.clearProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS);
        System.clearProperty(QueryGuardrails.KEY_MAX_ROWS);
        System.clearProperty(QueryGuardrails.KEY_ARROW_ALLOCATOR_BYTES);
    }

    @Test
    public void defaultsAreBounded() {
        // The pre-#1914 world: mondrian.rolap.queryTimeout=0, drillthrough uncapped,
        // new RootAllocator() == Long.MAX_VALUE. None of these may be 0/unbounded.
        assertEquals(300, QueryGuardrails.queryTimeoutSeconds());
        assertEquals(100_000, QueryGuardrails.maxRows());
        assertEquals(268_435_456L, QueryGuardrails.arrowAllocatorBytes());
    }

    @Test
    public void systemPropertyOverridesAreHonoured() {
        System.setProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS, "42");
        System.setProperty(QueryGuardrails.KEY_MAX_ROWS, "7");
        System.setProperty(QueryGuardrails.KEY_ARROW_ALLOCATOR_BYTES, "1024");
        assertEquals(42, QueryGuardrails.queryTimeoutSeconds());
        assertEquals(7, QueryGuardrails.maxRows());
        assertEquals(1024L, QueryGuardrails.arrowAllocatorBytes());
    }

    @Test
    public void unparseableOrHostileConfigFallsBackToTheDefault() {
        System.setProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS, "soon");
        System.setProperty(QueryGuardrails.KEY_MAX_ROWS, "0");
        System.setProperty(QueryGuardrails.KEY_ARROW_ALLOCATOR_BYTES, "-1");
        assertEquals(300, QueryGuardrails.queryTimeoutSeconds());
        assertEquals(100_000, QueryGuardrails.maxRows());
        assertEquals(268_435_456L, QueryGuardrails.arrowAllocatorBytes());

        System.setProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS, "-30");
        assertEquals("negative timeout falls back, doesn't disable", 300, QueryGuardrails.queryTimeoutSeconds());
    }

    @Test
    public void zeroTimeoutIsAnExplicitOptOut() {
        System.setProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS, "0");
        assertEquals(0, QueryGuardrails.queryTimeoutSeconds());
    }

    @Test
    public void configuredMaxRowsIsCappedByAHardCeiling() {
        // Even if an operator configures an absurd ceiling, maxRows() must not hand
        // Integer.MAX_VALUE back to the MDX emitter.
        System.setProperty(QueryGuardrails.KEY_MAX_ROWS, "2000000000");
        assertTrue(QueryGuardrails.maxRows() <= 10_000_000);
        assertTrue(QueryGuardrails.clampMaxRows(Integer.MAX_VALUE) <= 10_000_000);
    }

    @Test
    public void clampMaxRowsBoundsEveryRequest() {
        assertEquals("no cap requested → server ceiling", 100_000, QueryGuardrails.clampMaxRows(0));
        assertEquals("negative request → server ceiling", 100_000, QueryGuardrails.clampMaxRows(-5));
        assertEquals("small request passes through", 25, QueryGuardrails.clampMaxRows(25));
        assertEquals("large request is clamped", 100_000, QueryGuardrails.clampMaxRows(50_000_000));
    }

    @Test
    public void applyQueryTimeoutSetsTheTimeoutOnTheStatement() {
        AtomicInteger applied = new AtomicInteger(-1);
        QueryGuardrails.applyQueryTimeout(fakeStatement(null, applied));
        assertEquals(300, applied.get());
    }

    @Test
    public void applyQueryTimeoutSwallowsUnsupportedDrivers() {
        // A driver without the hook must not fail the query — mondrian.rolap.queryTimeout
        // still bounds it. This is the "no unhandled exception" contract.
        QueryGuardrails.applyQueryTimeout(
                fakeStatement(new SQLFeatureNotSupportedException("setQueryTimeout"), new AtomicInteger()));
    }

    @Test
    public void applyQueryTimeoutIsANoOpWhenDisabled() {
        System.setProperty(QueryGuardrails.KEY_QUERY_TIMEOUT_SECONDS, "0");
        AtomicInteger called = new AtomicInteger(0);
        QueryGuardrails.applyQueryTimeout(fakeStatement(null, called));
        assertEquals(0, called.get());
    }

    @Test
    public void applyQueryTimeoutToleratesNull() {
        QueryGuardrails.applyQueryTimeout((Statement) null);
    }

    /**
     * @param toThrow exception the fake {@code setQueryTimeout} raises, or null
     * @param sink receives the seconds argument when no exception is raised
     */
    private static Statement fakeStatement(SQLException toThrow, AtomicInteger sink) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("setQueryTimeout".equals(method.getName()) && args != null && args.length == 1) {
                if (toThrow != null) {
                    throw toThrow;
                }
                sink.set((Integer) args[0]);
                return null;
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            return null;
        };
        return (Statement)
                Proxy.newProxyInstance(QueryGuardrailsTest.class.getClassLoader(), new Class<?>[] {Statement.class}, h);
    }

    /** Keeps the unused-import checker honest about Method. */
    @SuppressWarnings("unused")
    private static Method unusedMarker() {
        return null;
    }
}
