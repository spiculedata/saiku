/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Mondrian runs every SQL statement under a fixed pool of permits ({@code mondrian.query.limit},
 * 40 in the shipped {@code mondrian.properties}). A drillthrough hands its permit back only when
 * the drillthrough {@code ResultSet} is closed, so a REST path that converts the rows and returns
 * without closing it loses one permit per call, and once the pool is empty every later query in
 * the JVM waits forever.
 *
 * <p>The pool is created once per JVM and this suite shares one JVM, so the test cannot boot with a
 * lower limit. Instead it holds all but {@value #LIMIT} permits for the duration of the test,
 * which behaves exactly like {@code mondrian.query.limit=3}, and then runs more raw drillthroughs
 * than that. Each one must answer within {@link #PER_REQUEST}, and the pool must be back to where
 * it started afterwards.
 */
public class RawDrillthroughStatementPoolIT {

    private static final int LIMIT = 3;
    private static final int RUNS = 10;
    private static final Duration PER_REQUEST = Duration.ofSeconds(20);

    private static final String RAW_DRILLTHROUGH_BODY =
            """
            {
              "name": "raw-dt-pool",
              "type": "MDX",
              "cube": {
                "connection": "unknown_foodmart",
                "catalog": "FoodMart",
                "schema": "FoodMart",
                "name": "Sales",
                "uniqueName": "[Sales]"
              },
              "mdx": "DRILLTHROUGH MAXROWS 1 SELECT FROM [Sales] WHERE ([Measures].[Store Sales], [Product].[Products].[Drink])"
            }
            """;

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void rawDrillthrough_moreCallsThanTheStatementLimit_allAnswer_andThePoolIsRestored() throws Exception {
        // Warm-up: makes sure the cube is loaded and the engine's SQL statement class is
        // initialised before its pool is read.
        assertEquals(200, postRawDrillthrough(Duration.ofSeconds(60)).statusCode());

        Semaphore pool = statementPool();
        int baseline = awaitPermitsAtLeast(pool, 0, Duration.ZERO);
        assertTrue("pool must have more than " + LIMIT + " permits, has " + baseline, baseline > LIMIT);

        int held = baseline - LIMIT;
        assertTrue("could not reserve " + held + " permits", pool.tryAcquire(held, 10, TimeUnit.SECONDS));
        try {
            for (int i = 1; i <= RUNS; i++) {
                HttpResponse<String> resp;
                try {
                    resp = postRawDrillthrough(PER_REQUEST);
                } catch (HttpTimeoutException e) {
                    fail("raw drillthrough " + i + " of " + RUNS + " got no answer within " + PER_REQUEST
                            + " with a statement limit of " + LIMIT + "; available permits: "
                            + pool.availablePermits());
                    return;
                }
                assertEquals("raw drillthrough " + i + " body=" + resp.body(), 200, resp.statusCode());
            }
            int after = awaitPermitsAtLeast(pool, LIMIT, Duration.ofSeconds(10));
            assertEquals("every raw drillthrough must give its statement permit back", LIMIT, after);
        } finally {
            pool.release(held);
        }
        assertEquals("pool must be back at its starting size", baseline, pool.availablePermits());
    }

    private static HttpResponse<String> postRawDrillthrough(Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/api/query/execute"))
                .timeout(timeout)
                .header("Authorization", harness.adminBasicAuth())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(RAW_DRILLTHROUGH_BODY, StandardCharsets.UTF_8))
                .build();
        return harness.send(req);
    }

    /** The engine's statement pool, read from the webapp class loader where Mondrian lives. */
    private static Semaphore statementPool() throws Exception {
        Class<?> sqlStatement = Class.forName("mondrian.rolap.SqlStatement", true, harness.webappClassLoader());
        Field f = sqlStatement.getDeclaredField("querySemaphore");
        f.setAccessible(true);
        return (Semaphore) f.get(null);
    }

    /**
     * Waits until the pool has at least {@code min} free permits (statements are released as the
     * response is completed, which can trail the HTTP answer slightly), then returns the count.
     */
    private static int awaitPermitsAtLeast(Semaphore pool, int min, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (pool.availablePermits() < min && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return pool.availablePermits();
    }
}
