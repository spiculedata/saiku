/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#2443 — explaining a cell must not close the OLAP connection other queries are running on.
 *
 * <p>The explain's SQL capture borrows the connection manager's cached connection for the
 * datasource, which (without per-user datasource security) every session and every user shares. It
 * used to close that connection on the way out, so an ordinary query running on the same datasource
 * at that moment failed with "Connection is closed". Two sessions explain cells while a third runs
 * ordinary queries on the same datasource; every ordinary query must succeed.
 *
 * <p>It runs on the Warehouse cube, which no other explain IT touches: it warms no segment or member
 * cache that {@code ExplainSqlCaptureIT} needs cold to see SQL emitted (saiku#2291), yet it is the
 * same FoodMart datasource, so the same cached connection.
 *
 * <p>Kept small on purpose (a few dozen explains and queries against the shared harness): on the
 * unfixed code nearly every ordinary query overlapping an explain failed, so this needs no volume
 * to go red.
 */
public class ExplainKeepsSharedConnectionIT {

    private static final int EXPLAIN_ROUNDS = 20;
    private static final int MAX_ORDINARY_QUERIES = 60;
    /**
     * Ordinary queries that must start and finish while explains are still running, so a pass means
     * they really shared the connection with explains in flight (on the unfixed code about 20 of
     * them overlapped per run, and nearly all failed).
     */
    private static final int MIN_OVERLAPPING_QUERIES = 5;

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    private static String execute(String name) {
        return """
                {
                  "name": "%s",
                  "cube": {
                    "connection": "unknown_foodmart",
                    "catalog": "FoodMart",
                    "schema": "FoodMart",
                    "name": "Warehouse",
                    "uniqueName": "[Warehouse]"
                  },
                  "mdx": "SELECT NON EMPTY {[Measures].[Warehouse Sales]} ON COLUMNS, NON EMPTY {[Warehouse].[Warehouses].[Country].Members} ON ROWS FROM [Warehouse]"
                }
                """
                .formatted(name);
    }

    private static String explain(String name) {
        return """
                {"queryName":"%s","position":{"row":0,"column":0},"includeSql":true,"includeDrivers":false,"includeNarrative":false}
                """
                .formatted(name);
    }

    private static HttpClient session() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private static HttpResponse<String> post(HttpClient session, String path, String body) throws Exception {
        return session.send(
                HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                        .header("Authorization", harness.adminBasicAuth())
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void ordinaryQueriesKeepSucceedingWhileOtherSessionsExplain() throws Exception {
        List<HttpClient> explainers = List.of(session(), session());
        for (int i = 0; i < explainers.size(); i++) {
            HttpResponse<String> executed =
                    post(explainers.get(i), "/rest/saiku/api/query/execute", execute("it-2443-explain-" + i));
            assertEquals("explainer execute failed: " + executed.body(), 200, executed.statusCode());
        }
        HttpClient ordinary = session();

        AtomicBoolean explaining = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(explainers.size());
        List<Future<Integer>> explainRuns = new ArrayList<>();
        try {
            for (int i = 0; i < explainers.size(); i++) {
                HttpClient client = explainers.get(i);
                String body = explain("it-2443-explain-" + i);
                explainRuns.add(pool.submit(() -> {
                    int ok = 0;
                    for (int round = 0; round < EXPLAIN_ROUNDS; round++) {
                        if (post(client, "/rest/saiku/api/ai/explain", body).statusCode() == 200) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            Thread watcher = new Thread(() -> {
                for (Future<Integer> f : explainRuns) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                        // reported below
                    }
                }
                explaining.set(false);
            });
            watcher.start();

            int ran = 0;
            int overlapped = 0;
            List<String> failures = new ArrayList<>();
            while (explaining.get() && ran < MAX_ORDINARY_QUERIES) {
                HttpResponse<String> resp =
                        post(ordinary, "/rest/saiku/api/query/execute", execute("it-2443-ordinary"));
                ran++;
                if (explaining.get()) {
                    // started and finished while the explains were still running
                    overlapped++;
                }
                if (resp.statusCode() != 200) {
                    String body = resp.body();
                    failures.add(resp.statusCode() + " " + body.substring(0, Math.min(200, body.length())));
                }
            }
            watcher.join(TimeUnit.MINUTES.toMillis(5));

            int explained = 0;
            for (Future<Integer> f : explainRuns) {
                explained += f.get(5, TimeUnit.MINUTES);
            }
            assertTrue(
                    "at least " + MIN_OVERLAPPING_QUERIES + " ordinary queries must run while explains are in flight,"
                            + " got " + overlapped + " of " + ran,
                    overlapped >= MIN_OVERLAPPING_QUERIES);
            assertEquals(
                    ran + " ordinary queries (" + overlapped + " fully inside the explain window) ran beside "
                            + explained + " explains; failures: " + failures,
                    0,
                    failures.size());
            assertEquals("every explain must succeed too", (long) EXPLAIN_ROUNDS * explainers.size(), (long) explained);
        } finally {
            pool.shutdownNow();
        }
    }
}
