/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/**
 * saiku#1549 — query depth/complexity limits on the GraphQL engine. The engine previously
 * built bare (no Instrumentation), so an authenticated user could submit a pathologically
 * deep or wide document with nothing bounding CPU/memory. These tests pin:
 * <ul>
 *   <li>the env &gt; property &gt; default resolution ladder (0 disables, invalid/negative
 *       fail SAFE to the default);</li>
 *   <li>live enforcement through {@code execute()} — an over-deep document is rejected with
 *       a GraphQL error, not executed;</li>
 *   <li>normal queries (serverInfo, introspection) pass untouched under the DEFAULTS, so
 *       real clients and GraphiQL are unaffected.</li>
 * </ul>
 */
public class SaikuGraphQlLimitsTest {

    @After
    public void clearProps() {
        System.clearProperty(SaikuGraphQlService.PROP_MAX_DEPTH);
        System.clearProperty(SaikuGraphQlService.PROP_MAX_COMPLEXITY);
    }

    private static SaikuGraphQlService boot() {
        ObjectMapper mapper = new ObjectMapper();
        SaikuGraphQlFetchers fetchers = new SaikuGraphQlFetchers(mapper);
        fetchers.setServerVersion("4.6.0-test");
        SaikuGraphQlService svc = new SaikuGraphQlService(mapper);
        svc.setFetchers(fetchers);
        svc.afterPropertiesSet();
        return svc;
    }

    // ── resolution ladder ────────────────────────────────────────────────────

    @Test
    public void defaultsApplyWhenNothingConfigured() {
        assertEquals(
                SaikuGraphQlService.DEFAULT_MAX_DEPTH,
                SaikuGraphQlService.resolveLimit(
                        k -> null, k -> null, "E", "p", SaikuGraphQlService.DEFAULT_MAX_DEPTH));
    }

    @Test
    public void envWinsOverProperty() {
        assertEquals(7, SaikuGraphQlService.resolveLimit(k -> "7", k -> "9", "E", "p", 30));
    }

    @Test
    public void propertyUsedWhenNoEnv() {
        assertEquals(9, SaikuGraphQlService.resolveLimit(k -> null, k -> "9", "E", "p", 30));
    }

    @Test
    public void zeroDisablesAndInvalidFailsSafeToDefault() {
        assertEquals(0, SaikuGraphQlService.resolveLimit(k -> "0", k -> null, "E", "p", 30));
        assertEquals(30, SaikuGraphQlService.resolveLimit(k -> "-5", k -> null, "E", "p", 30));
        assertEquals(30, SaikuGraphQlService.resolveLimit(k -> "unlimited", k -> null, "E", "p", 30));
    }

    @Test
    public void bothDisabledMeansNoInstrumentation() {
        assertNull(SaikuGraphQlService.buildLimitsInstrumentation(0, 0));
        assertNotNull(SaikuGraphQlService.buildLimitsInstrumentation(30, 0));
        assertNotNull(SaikuGraphQlService.buildLimitsInstrumentation(0, 10_000));
        assertNotNull(SaikuGraphQlService.buildLimitsInstrumentation(30, 10_000));
    }

    // ── live enforcement through execute() ───────────────────────────────────

    /** An introspection chain nested past the limit must be rejected, not executed. */
    @Test
    public void overDeepQueryIsRejected() {
        System.setProperty(SaikuGraphQlService.PROP_MAX_DEPTH, "5");
        SaikuGraphQlService svc = boot();

        // __schema { queryType { fields { type { ofType { ofType ... }}}}} — each level +1 depth.
        StringBuilder q = new StringBuilder("{ __schema { queryType { fields { type ");
        int nested = 8;
        for (int i = 0; i < nested; i++) {
            q.append("{ ofType ");
        }
        q.append("{ name }");
        for (int i = 0; i < nested; i++) {
            q.append(" }");
        }
        q.append(" } } } }");

        Map<String, Object> result = svc.execute(q.toString(), null, null);
        List<?> errors = (List<?>) result.get("errors");
        assertNotNull("an over-deep document must produce a GraphQL error", errors);
        assertTrue(
                "depth violation should be the reported cause — got: " + errors,
                errors.toString().toLowerCase().contains("depth"));
    }

    @Test
    public void overComplexQueryIsRejected() {
        System.setProperty(SaikuGraphQlService.PROP_MAX_COMPLEXITY, "2");
        SaikuGraphQlService svc = boot();

        // serverInfo { version mdxEnabled ossieEnabled } — complexity > 2 under the default
        // per-field calculator, so the tiny cap trips deterministically.
        Map<String, Object> result = svc.execute("{ serverInfo { version mdxEnabled ossieEnabled } }", null, null);
        List<?> errors = (List<?>) result.get("errors");
        assertNotNull("an over-complex document must produce a GraphQL error", errors);
        assertTrue(
                "complexity violation should be the reported cause — got: " + errors,
                errors.toString().toLowerCase().contains("complexity"));
    }

    /** Under the shipped DEFAULTS, normal usage — serverInfo and full introspection — passes. */
    @Test
    public void normalQueriesPassUnderDefaults() {
        SaikuGraphQlService svc = boot(); // no overrides -> defaults 30 / 10,000

        Map<String, Object> info = svc.execute("{ serverInfo { version mdxEnabled ossieEnabled } }", null, null);
        assertNull("serverInfo must not trip the limits — got: " + info.get("errors"), info.get("errors"));

        Map<String, Object> introspection = svc.execute("{ __schema { types { name } } }", null, null);
        assertNull(
                "introspection must not trip the limits — got: " + introspection.get("errors"),
                introspection.get("errors"));
    }
}
