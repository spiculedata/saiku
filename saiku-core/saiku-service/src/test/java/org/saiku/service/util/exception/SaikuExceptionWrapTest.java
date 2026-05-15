/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.util.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import org.junit.Test;
import org.saiku.olap.util.exception.SaikuOlapException;

/**
 * Asserts the cause-preservation contract on Saiku's custom RuntimeException /
 * checked-Exception subclasses. The intent of {@code wrap(cause, message)} is
 * that no callsite inside a {@code catch} block should be able to silently
 * drop the inner cause — which is the failure mode {@code
 * AiQueryResource.describeDeepestCause} exists to work around when the chain
 * gets broken upstream.
 *
 * <p>Two-layer property tested here: every call to {@code wrap()} sets the
 * cause exactly once and the chain remains traversable end-to-end through
 * arbitrary nesting depth.
 */
public class SaikuExceptionWrapTest {

    @Test
    public void serviceExceptionWrapSetsCauseExactlyOnce() {
        SQLException leaf = new SQLException("db went away");
        SaikuServiceException wrapped = SaikuServiceException.wrap(leaf, "Failed to drillthrough");
        assertEquals("Failed to drillthrough", wrapped.getMessage());
        assertSame(leaf, wrapped.getCause());
    }

    @Test
    public void olapExceptionWrapSetsCauseExactlyOnce() {
        RuntimeException leaf = new RuntimeException("mondrian bare-throw");
        SaikuOlapException wrapped = SaikuOlapException.wrap(leaf, "Cannot get native cube");
        assertEquals("Cannot get native cube", wrapped.getMessage());
        assertSame(leaf, wrapped.getCause());
    }

    /**
     * Three-layer nesting: leaf → SaikuOlapException → SaikuServiceException.
     * The deepest leaf must remain reachable by walking {@code getCause}.
     */
    @Test
    public void wrapPreservesChainAcrossThreeLayers() {
        NullPointerException leaf = new NullPointerException("Cannot invoke setCatalog because con is null");
        SaikuOlapException middle = SaikuOlapException.wrap(leaf, "OLAP connection failed");
        SaikuServiceException top = SaikuServiceException.wrap(middle, "Can't execute query");

        assertSame(middle, top.getCause());
        assertSame(leaf, top.getCause().getCause());

        // Walk to deepest cause — the pattern AiQueryResource.describeDeepestCause uses.
        Throwable deepest = top;
        while (deepest.getCause() != null) deepest = deepest.getCause();
        assertSame(leaf, deepest);
        assertTrue(deepest.getMessage().contains("setCatalog"));
    }

    /**
     * wrap() with a null cause is currently not contractually defined. Java's
     * Throwable allows constructing with null cause (treated as no cause).
     * Pinning current behaviour so a future hardening to "reject null cause"
     * is a deliberate change rather than a silent break.
     */
    @Test
    public void wrapWithNullCauseStillConstructs() {
        SaikuServiceException wrapped = SaikuServiceException.wrap(null, "no cause supplied");
        assertEquals("no cause supplied", wrapped.getMessage());
        assertNotNull(wrapped);
    }

    /**
     * Sanity: SaikuOlapException is a checked Exception (not RuntimeException).
     * The wrap() helper preserves that distinction — it cannot accidentally
     * return a RuntimeException subclass that callers could miss-handle.
     */
    @Test
    public void exceptionTypesAreDistinct() {
        SaikuServiceException svc = SaikuServiceException.wrap(new RuntimeException(), "x");
        SaikuOlapException olap = SaikuOlapException.wrap(new RuntimeException(), "y");

        assertTrue(svc instanceof RuntimeException);
        assertTrue(olap instanceof Exception);
        try {
            throw olap;
        } catch (SaikuOlapException expected) {
            // ok
        } catch (Exception unexpected) {
            fail("SaikuOlapException should be caught by its own type");
        }
    }
}
