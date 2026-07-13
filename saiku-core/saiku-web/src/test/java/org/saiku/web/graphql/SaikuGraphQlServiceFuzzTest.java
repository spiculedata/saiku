/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.graphql;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

/**
 * Fuzz tests for {@link SaikuGraphQlService#execute}.
 *
 * <p>The invariant: <em>whatever byte sequence a client sends as query / variables / extensions,
 * execute returns a well-formed GraphQL envelope Map (with either {@code data} or {@code errors}
 * populated) and NEVER throws.</em> A NullPointerException or a
 * ClassCastException that escapes would let a hostile client crash the request thread.
 *
 * <p>Also fuzzes the APQ protocol: random hash strings and random extension shapes must not
 * confuse the service into serving cached content under the wrong hash or crashing on a
 * malformed persistedQuery block.
 */
public class SaikuGraphQlServiceFuzzTest {

    private static final long SEED = 0xFEEDFACEL;

    private SaikuGraphQlService svc;

    @Before
    public void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        SaikuGraphQlFetchers fetchers = new SaikuGraphQlFetchers(mapper);
        fetchers.setServerVersion("4.6.0-fuzz");
        svc = new SaikuGraphQlService(mapper);
        svc.setFetchers(fetchers);
        svc.afterPropertiesSet();
    }

    @Test
    public void randomQueryTextNeverThrowsAndAlwaysReturnsEnvelope() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 3000; i++) {
            String query = GraphQlFuzzUtil.randomAscii(rng, 200);
            Map<String, Object> result;
            try {
                result = svc.execute(query, null, null);
            } catch (RuntimeException e) {
                fail("execute threw on random query '" + summarise(query) + "' (iteration " + i + "): " + e);
                return;
            }
            assertEnvelopeShape(result, "iteration " + i + " query '" + summarise(query) + "'");
        }
    }

    @Test
    public void randomVariablesNeverCrashExecutor() {
        Random rng = new Random(SEED + 1);
        String[] queries = {
            "{ serverInfo { version } }", "query V($x: String) { serverInfo { version } }", "{ cubes { cubeName } }",
        };
        for (int i = 0; i < 2000; i++) {
            String query = queries[rng.nextInt(queries.length)];
            Map<String, Object> variables = randomVariableBag(rng);
            Map<String, Object> result = svc.execute(query, variables, null);
            assertEnvelopeShape(result, "iteration " + i + " vars=" + variables);
        }
    }

    @Test
    public void randomExtensionsAreHandledSafely() {
        Random rng = new Random(SEED + 2);
        for (int i = 0; i < 2000; i++) {
            String query = "{ serverInfo { version } }";
            Map<String, Object> extensions = randomExtensions(rng);
            Map<String, Object> result = svc.execute(query, null, null, extensions);
            assertEnvelopeShape(result, "iteration " + i + " extensions=" + extensions);
        }
    }

    @Test
    public void randomApqHashNeverBoundToRandomQuery() {
        Random rng = new Random(SEED + 3);
        for (int i = 0; i < 1000; i++) {
            String query = "{ serverInfo { version } }";
            String badHash = randomHexHash(rng);
            Map<String, Object> extensions = Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", badHash));
            // Attempt to STORE with mismatched hash — must be refused, cache untouched.
            Map<String, Object> stored = svc.execute(query, null, null, extensions);
            assertNotNull(stored.get("errors"));
            // Follow-up lookup with the same bad hash must still miss.
            Map<String, Object> lookup = svc.execute(null, null, null, extensions);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errors = (List<Map<String, Object>>) lookup.get("errors");
            assertNotNull(errors);
            // On lookup, the miss reports PersistedQueryNotFound; on store, HASH_MISMATCH.
            Object errMessage = errors.get(0).get("message");
            assertNotNull(errMessage);
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private static void assertEnvelopeShape(Map<String, Object> envelope, String context) {
        assertNotNull("null envelope on " + context, envelope);
        boolean hasData = envelope.containsKey("data");
        boolean hasErrors = envelope.containsKey("errors");
        assertTrue("envelope must carry data or errors on " + context, hasData || hasErrors);
        if (hasErrors) {
            assertTrue("errors must be a list on " + context, envelope.get("errors") instanceof List);
        }
    }

    /** Generate a random variables map. Keys are ASCII, values are one of null/String/Number/List/Map. */
    private static Map<String, Object> randomVariableBag(Random rng) {
        int size = rng.nextInt(5);
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            m.put("k" + i, randomValue(rng, 0));
        }
        return m;
    }

    private static Object randomValue(Random rng, int depth) {
        int pick = rng.nextInt(depth > 2 ? 4 : 7);
        switch (pick) {
            case 0:
                return null;
            case 1:
                return rng.nextInt();
            case 2:
                return rng.nextDouble();
            case 3:
                return GraphQlFuzzUtil.randomAscii(rng, 20);
            case 4: {
                int n = rng.nextInt(4);
                List<Object> list = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(randomValue(rng, depth + 1));
                return list;
            }
            case 5: {
                Map<String, Object> nested = new LinkedHashMap<>();
                int n = rng.nextInt(3);
                for (int i = 0; i < n; i++) nested.put("n" + i, randomValue(rng, depth + 1));
                return nested;
            }
            default:
                return rng.nextBoolean();
        }
    }

    /** Random extensions block — mostly persistedQuery shapes but also junk. */
    private static Map<String, Object> randomExtensions(Random rng) {
        Map<String, Object> ext = new LinkedHashMap<>();
        int pick = rng.nextInt(6);
        switch (pick) {
            case 0:
                return ext; // empty
            case 1:
                ext.put("persistedQuery", "not-a-map");
                return ext;
            case 2:
                ext.put("persistedQuery", Map.of("version", "not-int", "sha256Hash", randomHexHash(rng)));
                return ext;
            case 3:
                ext.put("persistedQuery", Map.of());
                return ext;
            case 4:
                ext.put("persistedQuery", Map.of("version", 1, "sha256Hash", ""));
                return ext;
            default:
                ext.put("persistedQuery", Map.of("version", 1, "sha256Hash", randomHexHash(rng)));
                return ext;
        }
    }

    private static String randomHexHash(Random rng) {
        int len = rng.nextInt(80); // sometimes short, sometimes long — SHA-256 hashes are 64 chars
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(rng.nextInt(16)));
        }
        return sb.toString();
    }

    /** Truncate long random strings for readable failure messages. */
    private static String summarise(String s) {
        if (s == null) return "<null>";
        if (s.length() > 80) return s.substring(0, 80) + "…(" + s.length() + " chars)";
        return s;
    }
}
