/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.OssieQueryService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * IDOR tests for {@link OssieAsyncQueryService#getOwned(String, String, boolean)} — a foreign
 * caller must not be able to poll another user's queryId, whether the id exists or not (issue
 * #1403).
 *
 * <p>Uses a stub {@link OssieQueryService} so the async submit path executes synchronously
 * without a warehouse connection — we only care about ownership semantics here, not query
 * execution.
 *
 * <p>The security-context tests (saiku#2323, saiku#1968 parity) observe the roles the worker
 * thread resolves, both while the task runs and after it completes — without the fix the worker
 * sees no authentication at all and every {@code saiku.roles} control silently opens.
 */
public class OssieAsyncQueryServiceTest {

    private OssieAsyncQueryService async;

    @Before
    public void setUp() {
        async = new OssieAsyncQueryService(new StubOssieQueryService());
    }

    @After
    public void tearDown() {
        if (async != null) async.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    public void ownerCanRetrieveTheirOwnHandle() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        // Give the worker a moment to run.
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "alice", false);
        assertNotNull("owner poll should return the handle", result);
        assertEquals(h.getId(), result.getId());
    }

    @Test
    public void foreignPollReturnsNull() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "bob", false);
        assertNull("bob must not see alice's handle", result);
    }

    @Test
    public void unknownIdReturnsNullSameShapeAsForeignPoll() {
        // Same null-shape means the caller can't distinguish "not yours" from "doesn't exist"
        // — closes the IDOR-oracle vector.
        assertNull(async.getOwned("does-not-exist", "alice", false));
    }

    @Test
    public void adminCanRetrieveAnyHandle() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        OssieAsyncQueryService.Handle result = async.getOwned(h.getId(), "root", true);
        assertNotNull("admin poll should return the handle", result);
    }

    @Test
    public void cancelObeysOwnership() throws Exception {
        OssieAsyncQueryService.Handle h = async.submit(newQuery(), "alice");
        Thread.sleep(200);
        // Bob's cancel: 404-shape (returns false) — must NOT flip status to CANCELLED.
        assertTrue("bob's cancel should be rejected", !async.cancel(h.getId(), "bob", false));
        assertTrue(
                "handle status must not be CANCELLED after foreign cancel",
                h.getStatus() != OssieAsyncQueryService.Status.CANCELLED);
        // Alice's cancel succeeds (returns true).
        assertTrue("owner cancel should succeed", async.cancel(h.getId(), "alice", false));
    }

    // ---- saiku#2323 (CWE-863, saiku#1968 parity): the worker must run under the submitter's
    // Spring SecurityContext, and must not retain it after the task ends. ----

    @Test
    public void workerSeesSubmittersSecurityContext() throws Exception {
        RoleCapturingQueryService capturing = new RoleCapturingQueryService();
        OssieAsyncQueryService service = new OssieAsyncQueryService(capturing);
        try {
            authenticateAs("apac", "ROLE_APAC_ANALYST");
            OssieAsyncQueryService.Handle h =
                    service.submit(ossieQuery("geo", "geography", "region", "row_count"), "apac");
            awaitTerminal(h);
            assertEquals("worker must have run the task", 1, capturing.observations.size());
            Authentication seen = capturing.observations.get(0);
            assertNotNull("worker must observe the submitter's authentication", seen);
            assertEquals("apac", seen.getName());
            boolean hasRole = false;
            for (var a : seen.getAuthorities()) {
                if ("ROLE_APAC_ANALYST".equals(a.getAuthority())) hasRole = true;
            }
            assertTrue("worker must carry ROLE_APAC_ANALYST, saw " + seen.getAuthorities(), hasRole);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void workerContextIsClearedAfterTask() throws Exception {
        RoleCapturingQueryService capturing = new RoleCapturingQueryService();
        OssieAsyncQueryService service = new OssieAsyncQueryService(capturing);
        try {
            authenticateAs("apac", "ROLE_APAC_ANALYST");
            OssieAsyncQueryService.Handle first =
                    service.submit(ossieQuery("geo", "geography", "region", "row_count"), "apac");
            awaitTerminal(first);
            // A later submit from an unauthenticated context must resolve to no roles — the
            // pooled thread must not carry the previous task's context (saiku#1968 leak rule).
            SecurityContextHolder.clearContext();
            OssieAsyncQueryService.Handle second =
                    service.submit(ossieQuery("geo", "geography", "region", "row_count"), "anon");
            awaitTerminal(second);
            assertEquals("worker must have run both tasks", 2, capturing.observations.size());
            assertNotNull("first task must see the submitter's auth", capturing.observations.get(0));
            assertNull("no authentication may leak into the next pooled task", capturing.observations.get(1));
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void rowPredicateIsInjectedOnWorker() throws Exception {
        OssieModelDto semantic = geoModel(true, false);
        SqlCapturingQueryService capturing = new SqlCapturingQueryService(semantic);
        OssieAsyncQueryService service = new OssieAsyncQueryService(capturing);
        try {
            authenticateAs("apac", "ROLE_APAC_ANALYST");
            OssieAsyncQueryService.Handle h =
                    service.submit(ossieQuery("geo", "geography", "region", "row_count"), "apac");
            awaitTerminal(h);
            String sql = capturing.sql.get();
            assertNotNull("worker must have translated the shelf state", sql);
            assertTrue(
                    "async SQL must carry the row predicate, got: " + sql, sql.contains("REGION IN ('APAC', 'Japan')"));
            assertEquals(OssieAsyncQueryService.Status.DONE, h.getStatus());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void deniedFieldFailsOnWorker() throws Exception {
        OssieModelDto semantic = geoModel(false, true);
        SqlCapturingQueryService capturing = new SqlCapturingQueryService(semantic);
        OssieAsyncQueryService service = new OssieAsyncQueryService(capturing);
        try {
            authenticateAs("apac", "ROLE_APAC_ANALYST");
            OssieAsyncQueryService.Handle h =
                    service.submit(ossieQuery("geo", "geography", "account_manager", "row_count"), "apac");
            awaitTerminal(h);
            assertEquals(
                    "a denied field must never complete with data",
                    OssieAsyncQueryService.Status.FAILED,
                    h.getStatus());
            assertTrue(
                    "error must be the translator's access denial, got: " + h.getErrorMessage(),
                    String.valueOf(h.getErrorMessage()).contains("Access denied"));
        } finally {
            service.shutdown();
        }
    }

    private static ThinQuery newQuery() {
        ThinQuery tq = new ThinQuery();
        tq.setName("stub-" + System.nanoTime());
        tq.setQueryType("OSSIE");
        return tq;
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    /** Poll the handle to a terminal state, failing the test if it doesn't get there in time. */
    private static void awaitTerminal(OssieAsyncQueryService.Handle h) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            OssieAsyncQueryService.Status s = h.getStatus();
            if (s == OssieAsyncQueryService.Status.DONE
                    || s == OssieAsyncQueryService.Status.FAILED
                    || s == OssieAsyncQueryService.Status.CANCELLED) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("handle did not reach a terminal state in time, status=" + h.getStatus());
    }

    private static ThinQuery ossieQuery(String connection, String dataset, String field, String metric) {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection(connection);
        m.setModel(connection);
        m.setFactDataset(dataset);
        OssieQueryModel.FieldRef f = new OssieQueryModel.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        m.setRows(List.of(f));
        OssieQueryModel.MetricRef v = new OssieQueryModel.MetricRef();
        v.setMetric(metric);
        m.setValues(List.of(v));
        ThinQuery tq = new ThinQuery();
        tq.setName("ossie-async-test-" + System.nanoTime());
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(m);
        return tq;
    }

    /**
     * Minimal GEO model: a {@code geography} dataset with {@code region}, an APAC row predicate
     * when {@code withPredicate}, and a {@code ROLE_APAC_ANALYST}-denied {@code account_manager}
     * field when {@code withDeniedField}.
     */
    private static OssieModelDto geoModel(boolean withPredicate, boolean withDeniedField) {
        OssieModelDto semantic = new OssieModelDto();
        semantic.setName("GEO");
        OssieModelDto.Dataset geography = new OssieModelDto.Dataset();
        geography.setName("geography");
        OssieModelDto.Field region = new OssieModelDto.Field();
        region.setName("region");
        region.setExpression("REGION");
        geography.getFields().add(region);
        if (withDeniedField) {
            OssieModelDto.Field accountManager = new OssieModelDto.Field();
            accountManager.setName("account_manager");
            accountManager.setExpression("ACCOUNT_MANAGER");
            accountManager.setDenyRoles(List.of("ROLE_APAC_ANALYST"));
            geography.getFields().add(accountManager);
        }
        if (withPredicate) {
            geography
                    .getRowPredicates()
                    .add(new OssieModelDto.RowPredicate("ROLE_APAC_ANALYST", "REGION IN ('APAC', 'Japan')"));
        }
        semantic.getDatasets().add(geography);
        OssieModelDto.Metric rowCount = new OssieModelDto.Metric();
        rowCount.setName("row_count");
        rowCount.setExpression("COUNT(*)");
        semantic.getMetrics().add(rowCount);
        return semantic;
    }

    /** Records the Authentication the worker thread sees at execute() time, once per task. */
    static final class RoleCapturingQueryService extends OssieQueryService {
        final List<Authentication> observations = new CopyOnWriteArrayList<>();

        @Override
        public CellDataSet execute(ThinQuery tq) {
            observations.add(
                    SecurityContextHolder.getContext() == null
                            ? null
                            : SecurityContextHolder.getContext().getAuthentication());
            return null;
        }
    }

    /**
     * Runs the real {@code OssieShelfSqlTranslator} against a canned semantic model with the
     * roles resolved on the executing thread — no warehouse needed, since only the generated SQL
     * (or its access denial) is observed.
     */
    static final class SqlCapturingQueryService extends OssieQueryService {
        final AtomicReference<String> sql = new AtomicReference<>();

        SqlCapturingQueryService(OssieModelDto semantic) {
            setOssieDiscoverService(new OssieDiscoverService() {
                @Override
                public OssieModelDto getModel(String connectionName) {
                    return semantic;
                }
            });
        }

        @Override
        public CellDataSet execute(ThinQuery tq) {
            sql.set(previewSql(tq.getOssieQueryModel()));
            return null;
        }
    }

    /**
     * Minimal query service that returns null and completes fast. We're testing ownership, not
     * execution.
     */
    static final class StubOssieQueryService extends OssieQueryService {
        @Override
        public CellDataSet execute(ThinQuery tq) {
            return null;
        }
    }
}
