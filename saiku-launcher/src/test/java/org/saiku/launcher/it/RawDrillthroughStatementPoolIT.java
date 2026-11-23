/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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
 * the drillthrough {@code ResultSet} is closed, so a REST path that returns without closing it
 * loses one permit per call, and once the pool is used up later queries wait for a permit.
 *
 * <p>The pool is created once per JVM and this suite shares one JVM, so the test cannot boot with a
 * lower limit. Instead each test holds all but {@value #LIMIT} permits for its duration, which
 * behaves like {@code mondrian.query.limit=3}, and then runs more drillthroughs than that. Each one
 * must answer within {@link #PER_REQUEST}, and the pool must be back to where it started
 * afterwards. Together the tests issue about 20 drillthroughs, well under the shipped limit.
 */
public class RawDrillthroughStatementPoolIT {

    private static final int LIMIT = 3;
    private static final int RUNS = 10;
    private static final Duration PER_REQUEST = Duration.ofSeconds(20);

    private static final String CUBE =
            """
            "cube": {
                "connection": "unknown_foodmart",
                "catalog": "FoodMart",
                "schema": "FoodMart",
                "name": "Sales",
                "uniqueName": "[Sales]"
              }""";

    private static final String RAW_DRILLTHROUGH_BODY =
            """
            {
              "name": "raw-dt-pool",
              "type": "MDX",
              %s,
              "mdx": "DRILLTHROUGH MAXROWS 1 SELECT FROM [Sales] WHERE ([Measures].[Store Sales], [Product].[Products].[Drink])"
            }
            """
                    .formatted(CUBE);

    private static final String CELL_QUERY = "dt-pool-cell";

    private static final String CELL_QUERY_BODY =
            """
            {
              "name": "%s",
              "type": "MDX",
              %s,
              "mdx": "SELECT {[Measures].[Unit Sales], [Measures].[Store Sales]} ON 0, {[Product].[Products].[Product Family].Members} ON 1 FROM [Sales]"
            }
            """
                    .formatted(CELL_QUERY, CUBE);

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void rawDrillthrough_moreCallsThanTheStatementLimit_allAnswer_andThePoolIsRestored() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        // Warm-up: makes sure the cube is loaded and the engine's SQL statement class is
        // initialised before its pool is read.
        assertEquals(200, postRawDrillthrough(http, Duration.ofSeconds(60)).statusCode());

        runPastTheLimit("raw drillthrough", () -> {
            HttpResponse<String> resp = postRawDrillthrough(http, PER_REQUEST);
            assertEquals("raw drillthrough body=" + resp.body(), 200, resp.statusCode());
        });
    }

    /**
     * A cell drillthrough resolves column captions after it has opened the ResultSet. A
     * {@code returns} spelling that the engine accepts but the caption lookup cannot resolve makes
     * that lookup fail; the request answers with an error, and the ResultSet must still be closed.
     */
    @Test
    public void cellDrillthrough_whoseCaptionLookupFails_releasesItsStatement() throws Exception {
        HttpClient session =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> exec = session.send(
                request("/rest/saiku/api/query/execute", Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(CELL_QUERY_BODY, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("execute body=" + exec.body(), 200, exec.statusCode());

        String path = "/rest/saiku/api/query/" + CELL_QUERY + "/drillthrough?position=0:0&maxrows=1&returns="
                + URLEncoder.encode("[Product].[Product Family]", StandardCharsets.UTF_8);
        runPastTheLimit("cell drillthrough", () -> {
            // Only that it answers matters here; today the failed lookup answers with an error.
            session.send(request(path, PER_REQUEST).GET().build(), HttpResponse.BodyHandlers.ofString());
        });
    }

    private interface Call {
        void run() throws Exception;
    }

    /**
     * Holds all but {@link #LIMIT} statement permits, runs {@code call} {@link #RUNS} times, checks
     * every run answered and the free permits are back at {@link #LIMIT}, then releases the held
     * permits and checks the pool is back at its starting size.
     */
    private static void runPastTheLimit(String label, Call call) throws Exception {
        Semaphore pool = statementPool();
        int baseline = pool.availablePermits();
        assertTrue("pool must have more than " + LIMIT + " permits, has " + baseline, baseline > LIMIT);

        int held = baseline - LIMIT;
        assertTrue("could not reserve " + held + " permits", pool.tryAcquire(held, 10, TimeUnit.SECONDS));
        try {
            for (int i = 1; i <= RUNS; i++) {
                try {
                    call.run();
                } catch (HttpTimeoutException e) {
                    fail(label + " " + i + " of " + RUNS + " got no answer within " + PER_REQUEST
                            + " with a statement limit of " + LIMIT + "; available permits: "
                            + pool.availablePermits());
                    return;
                }
            }
            int after = awaitPermitsAtLeast(pool, LIMIT, Duration.ofSeconds(10));
            assertEquals("every " + label + " must give its statement permit back", LIMIT, after);
        } finally {
            pool.release(held);
        }
        assertEquals("pool must be back at its starting size", baseline, pool.availablePermits());
    }

    private static HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                .timeout(timeout)
                .header("Authorization", harness.adminBasicAuth())
                .header("Accept", "application/json");
    }

    private static HttpResponse<String> postRawDrillthrough(HttpClient http, Duration timeout) throws Exception {
        return http.send(
                request("/rest/saiku/api/query/execute", timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(RAW_DRILLTHROUGH_BODY, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
