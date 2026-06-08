/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;

import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.olap4j.CellSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.QueryContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1284 regression: the AI drillthrough <b>CSV export</b> endpoint must enforce the same
 * owner check as the JSON + column-discovery siblings. It previously resolved the async handle
 * with owner-agnostic {@code asyncQueryService.get(queryId)} and re-attached the result, so a
 * non-owner who guessed/leaked another user's {@code queryId} could export that user's
 * drillthrough fact rows (IDOR, CWE-639 — same family as #1208).
 *
 * <p>The security-observable signal is the <b>re-attach</b>: only an authorised caller may cause
 * the victim's CellSet to be registered onto the current session
 * ({@code ThinQueryService.registerExternalContext}). A non-owner must NOT. With the unowned
 * {@code get()} this test goes RED (re-attach fires for "mallory"); with the owner-scoped
 * {@code resolveDrillthroughName()}/{@code getOwned()} it is GREEN.
 */
public class AiDrillthroughCsvIdorTest {

    private final AtomicInteger reattachCount = new AtomicInteger();

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String principal) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "x", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    /** AsyncQueryService that lets the test seed a handle with an explicit owner + a ready result. */
    private static final class OwnedHandleAsyncService extends AsyncQueryService {
        String register(ThinQuery q, String owner) {
            String id = UUID.randomUUID().toString();
            AsyncQueryHandle h = new AsyncQueryHandle(id, q, owner);
            h.setStatus(AsyncQueryHandle.Status.DONE);
            h.setFuture(CompletableFuture.completedFuture(null));
            try {
                java.lang.reflect.Field f = AsyncQueryService.class.getDeclaredField("handles");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, AsyncQueryHandle> map = (Map<String, AsyncQueryHandle>) f.get(this);
                map.put(id, h);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return id;
        }
    }

    /** ThinQueryService that records re-attach and never runs a real drillthrough. */
    private ThinQueryService recordingThinQueryService() {
        return new ThinQueryService() {
            @Override
            public QueryContext getContext(String name) {
                return null; // force the re-attach branch for an authorised caller
            }

            @Override
            public void registerExternalContext(ThinQuery query, CellSet cellSet) {
                reattachCount.incrementAndGet();
            }

            @Override
            public ResultSet drillthrough(String queryName, int maxrows, Integer firstRowset, String returns) {
                throw new RuntimeException("stub: no live query"); // method returns an error envelope
            }

            @Override
            public ResultSet drillthrough(
                    String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
                throw new RuntimeException("stub: no live query");
            }
        };
    }

    @Test
    public void nonOwnerCannotReattachVictimCellSetViaCsvExport() {
        OwnedHandleAsyncService async = new OwnedHandleAsyncService();
        AiQueryResource resource = new AiQueryResource();
        resource.setAsyncQueryService(async);
        resource.setThinQueryService(recordingThinQueryService());

        ThinQuery victim = new ThinQuery();
        victim.setName("alice-drillthrough");
        victim.setMdx("SELECT FROM [Sales]");
        String queryId = async.register(victim, "alice");

        // mallory (non-owner, non-admin) tries to export alice's drillthrough.
        login("mallory");
        reattachCount.set(0);
        resource.drillthroughExportCsv(queryId, 100, null, null, null);
        assertEquals(
                "IDOR: a non-owner must NOT re-attach the victim's cellset for CSV export", 0, reattachCount.get());

        // Control: the owner still re-attaches + proceeds (the fix didn't break legit access).
        login("alice");
        reattachCount.set(0);
        resource.drillthroughExportCsv(queryId, 100, null, null, null);
        assertEquals("owner must still re-attach + proceed", 1, reattachCount.get());
    }
}
